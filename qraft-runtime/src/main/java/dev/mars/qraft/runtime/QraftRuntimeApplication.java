package dev.mars.qraft.runtime;

import dev.mars.qraft.agent.QraftAgent;
import dev.mars.qraft.controller.QraftControllerApplication;
import dev.mars.qraft.config.ConfigFileResolver;

import java.nio.file.Path;
import java.util.Arrays;

/** Single executable entry point for Qraft server and client modes. */
public final class QraftRuntimeApplication {
    private QraftRuntimeApplication() { }

    public static void main(String[] args) {
        RuntimeLifecycle lifecycle = run(
                args, QraftRuntimeApplication::launchServer, QraftRuntimeApplication::launchClient);
        Thread shutdownHook = Thread.ofPlatform().name("qraft-runtime-shutdown").unstarted(() ->
                lifecycle.closeAsync().join());
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        lifecycle.completion().join();
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
        QraftControllerApplication.RunningController controller =
                QraftControllerApplication.launch(configurationPath);
        return new ManagedRuntimeLifecycle(controller::closeAsync);
    }

    private static RuntimeLifecycle launchClient(Path configurationPath) {
        QraftAgent agent = QraftAgent.launch(configurationPath);
        return new ManagedRuntimeLifecycle(() -> agent.shutdown().thenApply(ignored -> null));
    }

    static Startup parseArguments(String[] args) {
        if (args == null || args.length == 0 || args[0] == null || args[0].isBlank()) {
            throw new IllegalArgumentException("Usage: qraft <server|client> [--config <path>]");
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
