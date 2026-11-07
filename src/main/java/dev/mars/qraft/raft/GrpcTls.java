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

package dev.mars.qraft.raft;

import dev.mars.qraft.common.tls.ServerTls;
import dev.mars.qraft.common.tls.TlsMaterial;
import io.grpc.netty.GrpcSslContexts;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * Netty TLS contexts for Qraft's gRPC listeners and channels, built from {@link TlsMaterial} (transport security
 * list, Phases 2 and 5). The JDK's TLS is used, so the versions and the trust are the material's own.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
public final class GrpcTls {
    private GrpcTls() { }

    /** A listener's context: its certificate, the CA, and whether a caller must present a certificate. */
    public static SslContext serverContext(ServerTls tls) {
        TlsMaterial material = tls.material();
        try {
            SslContextBuilder builder = SslContextBuilder.forServer(material.keyManagers().orElseThrow(
                            () -> new IllegalStateException(material.settings().location()
                                    + ".certFile and .keyFile are required to serve TLS")))
                    .sslProvider(SslProvider.JDK)
                    .trustManager(material.trustManagers())
                    .protocols(material.settings().protocols())
                    .clientAuth(tls.requireClientCertificate() ? ClientAuth.REQUIRE : ClientAuth.NONE);
            return GrpcSslContexts.configure(builder, SslProvider.JDK).build();
        } catch (SSLException e) {
            throw new IllegalStateException(material.settings().location() + " cannot serve gRPC TLS: "
                    + e.getMessage(), e);
        }
    }

    /**
     * A Raft peer channel's context: this server's certificate, the CA, and the check that the peer's certificate
     * names the host dialled unless {@code tls} turns it off (decision 4).
     */
    public static SslContext peerContext(RaftTls tls) {
        TlsMaterial material = tls.material();
        try {
            SslContextBuilder builder = GrpcSslContexts.configure(SslContextBuilder.forClient(), SslProvider.JDK)
                    .protocols(material.settings().protocols())
                    .keyManager(material.keyManagers().orElseThrow(() -> new IllegalStateException(
                            material.settings().location() + ".certFile and .keyFile are required for the Raft port")));
            if (tls.verifyServerHostname()) {
                builder.trustManager(material.trustManagers());
            } else {
                builder.trustManager(new AnyHostTrustManager(x509(material.trustManagers())));
            }
            return builder.build();
        } catch (SSLException e) {
            throw new IllegalStateException(material.settings().location() + " cannot call the Raft port over TLS: "
                    + e.getMessage(), e);
        }
    }

    private static X509TrustManager x509(TrustManagerFactory factory) {
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) return x509;
        }
        throw new IllegalStateException("no X.509 trust manager");
    }

    /**
     * Checks a peer's certificate chain against the CA and nothing else: gRPC asks the engine to check the host
     * dialled, and this manager, being extended, answers for the engine without doing so.
     */
    private static final class AnyHostTrustManager extends X509ExtendedTrustManager {
        private final X509TrustManager delegate;

        AnyHostTrustManager(X509TrustManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }
    }

    /** A channel's context: the CA, and this side's certificate when the material has one. */
    public static SslContext clientContext(TlsMaterial material) {
        try {
            SslContextBuilder builder = GrpcSslContexts.configure(SslContextBuilder.forClient(), SslProvider.JDK)
                    .trustManager(material.trustManagers())
                    .protocols(material.settings().protocols());
            material.keyManagers().ifPresent(builder::keyManager);
            return builder.build();
        } catch (SSLException e) {
            throw new IllegalStateException(material.settings().location() + " cannot call gRPC over TLS: "
                    + e.getMessage(), e);
        }
    }
}
