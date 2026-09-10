package dev.mars.qraft.runtime;

import dev.mars.qraft.agent.QraftAgent;
import dev.mars.qraft.controller.QraftControllerApplication;

import java.util.Locale;

/** Single executable entry point for Qraft server and client modes. */
public final class QraftRuntimeApplication {
    private QraftRuntimeApplication() { }

    public static void main(String[] args) {
        String mode = resolveMode(args, System.getenv("QRAFT_MODE"));
        switch (mode) {
            case "server" -> QraftControllerApplication.main(new String[0]);
            case "client" -> QraftAgent.main(new String[0]);
            default -> throw new IllegalArgumentException(
                    "Unsupported Qraft mode '" + mode + "'. Use 'server' or 'client'.");
        }
    }

    static String resolveMode(String[] args, String environmentMode) {
        if (args.length > 1) {
            throw new IllegalArgumentException("Usage: qraft [server|client]");
        }
        String value = args.length == 1 ? args[0] : environmentMode;
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Qraft startup mode is required. Use 'server' or 'client'.");
        }
        if (value.startsWith("--mode=")) value = value.substring("--mode=".length());
        value = value.trim().toLowerCase(Locale.ROOT);
        if (!value.equals("server") && !value.equals("client")) {
            throw new IllegalArgumentException(
                    "Unsupported Qraft mode '" + value + "'. Use 'server' or 'client'.");
        }
        return value;
    }
}
