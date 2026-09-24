package dev.mars.qraft.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Resolves a Qraft JSON configuration file without consulting environment variables. */
public final class ConfigFileResolver {
    public static final String CONFIG_FILE_PROPERTY = "qraft.config";

    private ConfigFileResolver() { }

    /**
     * Resolves configuration in descending precedence: {@code --config}, the
     * {@code qraft.config} JVM property, then the conventional local and system paths.
     */
    public static Path resolve(String[] args, String role) {
        return resolve(args, role, System.getProperty(CONFIG_FILE_PROPERTY), Files::isRegularFile);
    }

    static Path resolve(String[] args, String role, String propertyValue, Predicate<Path> isRegularFile) {
        Objects.requireNonNull(isRegularFile, "isRegularFile");
        String normalizedRole = requireRole(role);
        String[] suppliedArguments = args == null ? new String[0] : args;

        if (suppliedArguments.length == 2 && "--config".equals(suppliedArguments[0])) {
            return requirePath(suppliedArguments[1], "--config");
        }
        if (suppliedArguments.length != 0) {
            throw usage(normalizedRole);
        }

        if (propertyValue != null) {
            return requirePath(propertyValue, "-D" + CONFIG_FILE_PROPERTY);
        }

        List<Path> defaults = defaultLocations(normalizedRole);
        return defaults.stream()
                .filter(isRegularFile)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No Qraft " + normalizedRole + " configuration file found. Use --config <path>, "
                                + "-D" + CONFIG_FILE_PROPERTY + "=<path>, or create one at " + defaults));
    }

    public static List<Path> defaultLocations(String role) {
        String normalizedRole = requireRole(role);
        return List.of(
                Path.of("config", normalizedRole + ".json"),
                Path.of("/etc/qraft", normalizedRole + ".json"));
    }

    private static Path requirePath(String value, String source) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(source + " must name a non-blank JSON configuration file");
        }
        return Path.of(value.trim());
    }

    private static String requireRole(String role) {
        if (!"server".equals(role) && !"client".equals(role)) {
            throw new IllegalArgumentException("Configuration role must be 'server' or 'client'");
        }
        return role;
    }

    private static IllegalArgumentException usage(String role) {
        return new IllegalArgumentException("Usage: qraft-" + role + " [--config <path>]");
    }
}
