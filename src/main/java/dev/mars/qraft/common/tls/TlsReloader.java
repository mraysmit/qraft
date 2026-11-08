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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Reloads TLS material when its files change (certificate reloading list, decisions 2 and 3): every interval it reads
 * the modification time and size of the CA, certificate and key files, and when one differs from the last check it
 * reloads the material. A reload is logged at INFO with the certificate's subject and expiry; a reload the material
 * refuses is logged at WARN with the reason, once until the files change again, and the material in use stays. A
 * certificate copied before its key is therefore refused once, and taken up when the key follows.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class TlsReloader implements AutoCloseable {
    /** How often the files are checked. */
    public static final Duration INTERVAL = Duration.ofSeconds(30);
    private static final Logger LOG = LoggerFactory.getLogger(TlsReloader.class);

    /** One file as a check saw it: missing, or its modification time and size. */
    private record Stamp(Path file, long modified, long size) { }

    private final TlsMaterial material;
    private final ScheduledExecutorService checks;
    private List<Stamp> seen;

    private TlsReloader(TlsMaterial material) {
        this.material = Objects.requireNonNull(material, "material");
        this.seen = stamps();
        this.checks = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "qraft-tls-reloader");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Checks {@code material}'s files every {@code interval}, from now until {@link #close()}. */
    public static TlsReloader start(TlsMaterial material, Duration interval) {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive: " + interval);
        }
        TlsReloader reloader = new TlsReloader(material);
        reloader.checks.scheduleWithFixedDelay(reloader::check, interval.toMillis(), interval.toMillis(),
                TimeUnit.MILLISECONDS);
        return reloader;
    }

    /** Reloads the material if one of its files has changed since the last check. */
    synchronized void check() {
        List<Stamp> now = stamps();
        if (now.equals(seen)) return;
        seen = now;
        String location = material.settings().location();
        try {
            material.reload();
        } catch (RuntimeException refused) {
            LOG.warn("{} was not reloaded, and the material in use stays: {}", location, refused.getMessage());
            return;
        }
        X509Certificate certificate = material.certificate().orElse(null);
        if (certificate == null) {
            LOG.info("{} reloaded: its CA bundle", location);
        } else {
            LOG.info("{} reloaded: certificate {}, valid until {}", location,
                    certificate.getSubjectX500Principal().getName(), certificate.getNotAfter().toInstant());
        }
    }

    private List<Stamp> stamps() {
        TlsSettings settings = material.settings();
        List<Path> files = new ArrayList<>();
        files.add(settings.caFile());
        settings.certFile().ifPresent(files::add);
        settings.keyFile().ifPresent(files::add);
        List<Stamp> stamps = new ArrayList<>(files.size());
        for (Path file : files) {
            try {
                stamps.add(new Stamp(file, Files.getLastModifiedTime(file).toMillis(), Files.size(file)));
            } catch (IOException missing) {
                // A file being replaced may be missing for a moment; that is a change too, and the reload says why.
                stamps.add(new Stamp(file, -1, -1));
            }
        }
        return List.copyOf(stamps);
    }

    @Override
    public void close() {
        checks.shutdownNow();
    }
}
