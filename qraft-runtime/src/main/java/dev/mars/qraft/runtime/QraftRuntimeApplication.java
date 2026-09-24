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
        Startup startup = parseArguments(args);
        String[] modeArguments = {"--config", startup.configPath().toString()};
        switch (startup.mode()) {
            case "server" -> QraftControllerApplication.main(modeArguments);
            case "client" -> QraftAgent.main(modeArguments);
            default -> throw new IllegalArgumentException(
                    "Unsupported Qraft mode '" + startup.mode() + "'. Use 'server' or 'client'.");
        }
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

    record Startup(String mode, Path configPath) { }
}
