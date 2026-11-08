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

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Objects;

/**
 * A key manager whose material can be swapped while contexts built on it are in use (certificate reloading list,
 * decision 1). A handshake chooses an alias in one call and asks for its chain and key in others, so each alias is
 * tagged with the generation of material that chose it and answered from that generation, the current or the one
 * before it; a swap during a handshake therefore never pairs one generation's choice with another's key.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
final class ReloadingKeyManager extends X509ExtendedKeyManager {
    private record Generation(long id, X509ExtendedKeyManager manager) { }

    /** An alias as the generation that chose it knows it. */
    private record Chosen(Generation generation, String alias) { }

    private volatile Generation current;
    private volatile Generation previous;

    ReloadingKeyManager(X509ExtendedKeyManager initial) {
        this.current = new Generation(1, Objects.requireNonNull(initial, "initial"));
    }

    /** Makes {@code next} the material later handshakes choose from. */
    synchronized void swap(X509ExtendedKeyManager next) {
        Generation now = current;
        previous = now;
        current = new Generation(now.id() + 1, Objects.requireNonNull(next, "next"));
    }

    private static String tag(Generation generation, String alias) {
        return alias == null ? null : generation.id() + ":" + alias;
    }

    private static String[] tag(Generation generation, String[] aliases) {
        if (aliases == null) return null;
        return Arrays.stream(aliases).map(alias -> tag(generation, alias)).toArray(String[]::new);
    }

    /** The generation that chose {@code alias} and the alias it chose, or {@code null} for one neither knows. */
    private Chosen untag(String alias) {
        if (alias == null) return null;
        int colon = alias.indexOf(':');
        if (colon < 1) return null;
        long id;
        try {
            id = Long.parseLong(alias.substring(0, colon));
        } catch (NumberFormatException notTagged) {
            return null;
        }
        Generation now = current;
        Generation before = previous;
        Generation chose = now.id() == id ? now : before != null && before.id() == id ? before : null;
        return chose == null ? null : new Chosen(chose, alias.substring(colon + 1));
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
        Generation now = current;
        return tag(now, now.manager().getClientAliases(keyType, issuers));
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
        Generation now = current;
        return tag(now, now.manager().chooseClientAlias(keyType, issuers, socket));
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
        Generation now = current;
        return tag(now, now.manager().getServerAliases(keyType, issuers));
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
        Generation now = current;
        return tag(now, now.manager().chooseServerAlias(keyType, issuers, socket));
    }

    @Override
    public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
        Generation now = current;
        return tag(now, now.manager().chooseEngineClientAlias(keyType, issuers, engine));
    }

    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
        Generation now = current;
        return tag(now, now.manager().chooseEngineServerAlias(keyType, issuers, engine));
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
        Chosen chosen = untag(alias);
        return chosen == null ? null : chosen.generation().manager().getCertificateChain(chosen.alias());
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
        Chosen chosen = untag(alias);
        return chosen == null ? null : chosen.generation().manager().getPrivateKey(chosen.alias());
    }
}
