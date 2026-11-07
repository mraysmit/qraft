/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.mars.qraft.common.tls;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PEM TLS material, read once, and the contexts built from it (transport security list, Phase 1). The CA bundle
 * decides which peers are trusted; the certificate and key, when set, are what this side presents. A refusal names
 * the setting and the file, and never holds a key's content.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
public final class TlsMaterial {
    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z0-9 ]+)-----([^-]*)-----END \\1-----");
    private static final char[] STORE_PASSWORD = "qraft-in-memory".toCharArray();
    private static final String[] KEY_ALGORITHMS = {"RSA", "EC"};

    private final TlsSettings settings;
    private final SSLContext clientContext;
    private final SSLContext serverContext;
    private final TrustManagerFactory trust;
    private final KeyManagerFactory keys;

    private TlsMaterial(TlsSettings settings, SSLContext clientContext, SSLContext serverContext,
                        TrustManagerFactory trust, KeyManagerFactory keys) {
        this.settings = settings;
        this.clientContext = clientContext;
        this.serverContext = serverContext;
        this.trust = trust;
        this.keys = keys;
    }

    /** Reads the files {@code settings} names; refuses one that is missing or does not hold what it should. */
    public static TlsMaterial load(TlsSettings settings) {
        Objects.requireNonNull(settings, "settings");
        try {
            KeyStore trusted = emptyStore();
            List<X509Certificate> authorities = certificates(settings, "caFile", settings.caFile());
            for (int index = 0; index < authorities.size(); index++) {
                trusted.setCertificateEntry("ca-" + index, authorities.get(index));
            }
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(trusted);

            KeyManagerFactory keys = null;
            if (settings.certFile().isPresent() && settings.keyFile().isPresent()) {
                List<X509Certificate> chain = certificates(settings, "certFile", settings.certFile().get());
                PrivateKey key = privateKey(settings, settings.keyFile().get());
                if (!matches(key, chain.getFirst())) {
                    throw new IllegalStateException(settings.location() + ".keyFile is not the key of "
                            + settings.location() + ".certFile: " + settings.keyFile().get());
                }
                KeyStore own = emptyStore();
                own.setKeyEntry("qraft", key, STORE_PASSWORD, chain.toArray(new Certificate[0]));
                keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keys.init(own, STORE_PASSWORD);
            }
            SSLContext client = SSLContext.getInstance("TLS");
            client.init(keys == null ? null : keys.getKeyManagers(), trust.getTrustManagers(), null);
            SSLContext server = null;
            if (keys != null) {
                server = SSLContext.getInstance("TLS");
                server.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
            }
            return new TlsMaterial(settings, client, server, trust, keys);
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException(settings.location() + " cannot be used: " + e.getMessage(), e);
        }
    }

    /** The context for serving TLS, with this side's certificate; refused when none is set. */
    public SSLContext serverContext() {
        if (serverContext == null) {
            throw new IllegalStateException(settings.location() + ".certFile and " + settings.location()
                    + ".keyFile are required to serve TLS");
        }
        return serverContext;
    }

    /** The context for calling a TLS server, presenting this side's certificate when one is set. */
    public SSLContext clientContext() {
        return clientContext;
    }

    /** The trust managers of the CA bundle, for a TLS stack that takes them rather than a context. */
    public TrustManagerFactory trustManagers() {
        return trust;
    }

    /** The key managers of this side's certificate, when one is set. */
    public java.util.Optional<KeyManagerFactory> keyManagers() {
        return java.util.Optional.ofNullable(keys);
    }

    /** The settings this material was read from. */
    public TlsSettings settings() {
        return settings;
    }

    /** A listener's parameters: the allowed versions, and whether a client certificate is required. */
    public SSLParameters serverParameters(boolean requireClientCertificate) {
        SSLParameters parameters = new SSLParameters();
        parameters.setProtocols(settings.protocols().toArray(String[]::new));
        parameters.setNeedClientAuth(requireClientCertificate);
        return parameters;
    }

    /** A caller's parameters: the allowed versions, and whether the server's certificate must name its host. */
    public SSLParameters clientParameters(boolean verifyHostname) {
        SSLParameters parameters = new SSLParameters();
        parameters.setProtocols(settings.protocols().toArray(String[]::new));
        if (verifyHostname) parameters.setEndpointIdentificationAlgorithm("HTTPS");
        return parameters;
    }

    /** Whether {@code key} signs what {@code certificate}'s public key verifies. */
    private static boolean matches(PrivateKey key, X509Certificate certificate) throws GeneralSecurityException {
        if (!key.getAlgorithm().equals(certificate.getPublicKey().getAlgorithm())) return false;
        String algorithm = "EC".equals(key.getAlgorithm()) ? "SHA256withECDSA" : "SHA256withRSA";
        byte[] probe = "qraft key check".getBytes(StandardCharsets.US_ASCII);
        Signature signer = Signature.getInstance(algorithm);
        signer.initSign(key);
        signer.update(probe);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance(algorithm);
        verifier.initVerify(certificate.getPublicKey());
        verifier.update(probe);
        return verifier.verify(signature);
    }

    private static KeyStore emptyStore() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        return store;
    }

    private static String read(TlsSettings settings, String field, Path file) {
        try {
            return Files.readString(file, StandardCharsets.US_ASCII);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(settings.location() + "." + field + " cannot be read: " + file, e);
        }
    }

    private static List<X509Certificate> certificates(TlsSettings settings, String field, Path file) {
        String text = read(settings, field, file);
        List<X509Certificate> certificates = new ArrayList<>();
        try {
            Collection<? extends Certificate> read = CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII)));
            for (Certificate certificate : read) certificates.add((X509Certificate) certificate);
        } catch (CertificateException e) {
            certificates.clear();
        }
        if (certificates.isEmpty()) {
            throw new IllegalStateException(settings.location() + "." + field + " holds no PEM certificate: " + file);
        }
        return certificates;
    }

    /** The PKCS #8 key in {@code file}; no refusal carries the file's content or an exception that might. */
    private static PrivateKey privateKey(TlsSettings settings, Path file) {
        String text = read(settings, "keyFile", file);
        Matcher block = PEM_BLOCK.matcher(text);
        String noKey = settings.location() + ".keyFile holds no PKCS #8 RSA or EC private key: " + file;
        if (!block.find()) throw new IllegalStateException(noKey);
        if (!"PRIVATE KEY".equals(block.group(1))) {
            throw new IllegalStateException(settings.location() + ".keyFile must hold a PKCS #8 key "
                    + "(BEGIN PRIVATE KEY), not BEGIN " + block.group(1) + ": " + file);
        }
        byte[] encoded;
        try {
            encoded = Base64.getMimeDecoder().decode(block.group(2).strip());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException(noKey);
        }
        for (String algorithm : KEY_ALGORITHMS) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(encoded));
            } catch (InvalidKeySpecException notThisAlgorithm) {
                // Try the next algorithm; a key that none reads is refused below.
            } catch (GeneralSecurityException unavailable) {
                throw new IllegalStateException(settings.location() + ".keyFile cannot be read with " + algorithm
                        + ": " + file);
            }
        }
        throw new IllegalStateException(noKey);
    }
}
