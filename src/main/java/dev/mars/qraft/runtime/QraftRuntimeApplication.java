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

package dev.mars.qraft.runtime;

import dev.mars.qraft.client.QraftClient;
import dev.mars.qraft.server.QraftServerApplication;
import dev.mars.qraft.common.config.ConfigFileResolver;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * Single executable entry point for Qraft server and client modes.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-10
 * @version 1.0
 */
public final class QraftRuntimeApplication {
    private QraftRuntimeApplication() { }

    public static void main(String[] args) {
        if (args != null && args.length > 0 && "operator".equals(args[0])) {
            System.exit(RaftOperatorCommand.run(Arrays.copyOfRange(args, 1, args.length), System.out, System.err));
        }
        RuntimeLifecycle lifecycle = launch(args);
        Thread shutdownHook = Thread.ofPlatform().name("qraft-runtime-shutdown").unstarted(() ->
                lifecycle.closeAsync().join());
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        lifecycle.completion().join();
    }

    /** Starts one configured Qraft mode and returns its runtime-owned lifecycle. */
    public static RuntimeLifecycle launch(String[] args) {
        return run(args, QraftRuntimeApplication::launchServer, QraftRuntimeApplication::launchClient);
    }

    static RuntimeLifecycle run(String[] args, ModeLauncher serverLauncher, ModeLauncher clientLauncher) {
        Startup startup = parseArguments(args);
        return switch (startup.mode()) {
            case "server" -> serverLauncher.launch(startup.configPath());
            case "client" -> clientLauncher.launch(startup.configPath());
            default -> throw new IllegalArgumentException(
                    "Unsupported Qraft mode '" + startup.mode() + "'. Use 'server' or 'client'.");
        };
    }

    private static RuntimeLifecycle launchServer(Path configurationPath) {
        QraftServerApplication.RunningServer server =
                QraftServerApplication.launch(configurationPath);
        return new ManagedRuntimeLifecycle(server::closeAsync, server::boundPorts);
    }

    private static RuntimeLifecycle launchClient(Path configurationPath) {
        QraftClient client = QraftClient.launch(configurationPath);
        return new ManagedRuntimeLifecycle(() -> client.shutdown().thenApply(ignored -> null),
                () -> java.util.Map.of("http", client.healthService().port()));
    }

    static Startup parseArguments(String[] args) {
        if (args == null || args.length == 0 || args[0] == null || args[0].isBlank()) {
            throw new IllegalArgumentException(
                    "Usage: qraft <server|client> [--config <path>], or qraft operator raft <command>");
        }
        String mode = args[0].trim().toLowerCase(java.util.Locale.ROOT);
        if (!mode.equals("server") && !mode.equals("client")) {
            throw new IllegalArgumentException(
                    "Unsupported Qraft mode '" + mode + "'. Use 'server' or 'client'.");
        }
        String[] configArguments = Arrays.copyOfRange(args, 1, args.length);
        return new Startup(mode, ConfigFileResolver.resolve(configArguments, mode));
    }

    @FunctionalInterface
    interface ModeLauncher {
        RuntimeLifecycle launch(Path configurationPath);
    }

    record Startup(String mode, Path configPath) { }
}
