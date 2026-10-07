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

package dev.mars.qraft.testing.fault;

import ch.qos.logback.classic.Level;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test logging helper used by {@link DockerLogExtensionHelper} to capture, archive,
 * audit, label, and reprint container output while a Docker test class is running.
 * Reports unexpected errors so the extension can fail the calling test class.
 */
public final class DockerLogCaptureHelper {
    private static final Object LOCK = new Object();
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d]*m");
    private static final Pattern EVENT = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\[[^]]+] "
                    + "(?<level>TRACE|DEBUG|INFO|WARN|ERROR)\\s+"
                    + "(?<logger>\\S+?)(?: \\[[^]]*])*(?: \\[[^]]*])? - (?<message>.*)$");
    private static final Pattern ERROR_TOKEN = Pattern.compile("(?:^|[|\\s-])ERROR(?:[|:\\s-]|$)");
    private static final Map<String, String> PREVIOUS_OUTPUT = new HashMap<>();

    private static String owner;
    private static Set<IntentionalErrorFixture> declared = Set.of();
    private static final Map<String, StringBuilder> captured = new LinkedHashMap<>();

    private DockerLogCaptureHelper() {
    }

    /** Starts collection for one Docker test class. Previously drained container output remains remembered. */
    public static void beginClass(String classOwner, Set<IntentionalErrorFixture> expected) {
        synchronized (LOCK) {
            if (owner != null) throw new IllegalStateException("Docker log capture already belongs to " + owner);
            owner = safeName(classOwner);
            declared = expected.isEmpty() ? Set.of() : EnumSet.copyOf(expected);
            for (IntentionalErrorFixture error : declared) {
                if (error.kind() != IntentionalErrorFixture.Kind.INTENTIONAL_ERROR) {
                    throw new IllegalArgumentException("Docker logs cannot declare injected failure " + error);
                }
            }
            captured.clear();
        }
    }

    /** Captures only output not already drained from this container or detached process. */
    public static void capture(String sourceId, String sourceName, String completeOutput) {
        synchronized (LOCK) {
            if (owner == null || completeOutput == null) return;
            String previous = PREVIOUS_OUTPUT.getOrDefault(sourceId, "");
            String addition = completeOutput.startsWith(previous)
                    ? completeOutput.substring(previous.length()) : completeOutput;
            PREVIOUS_OUTPUT.put(sourceId, completeOutput);
            StringBuilder destination = captured.computeIfAbsent(
                    safeName(sourceName), ignored -> new StringBuilder());
            if (!addition.isEmpty()) {
                destination.append(addition);
                if (!addition.endsWith("\n")) destination.append(System.lineSeparator());
            }
        }
    }

    /** Archives and checks everything captured for the current class, returning violations. */
    public static List<String> finishClass(Path logRoot) {
        synchronized (LOCK) {
            if (owner == null) return List.of("Docker log capture ended without a running Docker class");
            List<String> problems = new ArrayList<>();
            Set<IntentionalErrorFixture> observed = EnumSet.noneOf(IntentionalErrorFixture.class);
            List<ExternalError> recognised = new ArrayList<>();
            Path classDirectory = logRoot.resolve(owner);
            try {
                Files.createDirectories(classDirectory);
                for (Map.Entry<String, StringBuilder> entry : captured.entrySet()) {
                    String output = entry.getValue().toString();
                    Audit audit = audit(output, declared, owner);
                    Files.writeString(classDirectory.resolve(entry.getKey() + ".log"), audit.archivedOutput(),
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                    recognised.addAll(audit.recognised());
                    problems.addAll(audit.problems().stream()
                            .map(problem -> entry.getKey() + ": " + problem).toList());
                    audit.recognised().forEach(event -> observed.add(event.error()));
                }
                observed.forEach(IntentionalErrorsHelper::expect);
                recognised.forEach(event -> LoggerFactory.getLogger(event.logger()).error(event.message()));
            } catch (IOException error) {
                problems.add("could not archive Docker logs for " + owner + ": " + error.getMessage());
            } finally {
                owner = null;
                declared = Set.of();
                captured.clear();
            }
            return List.copyOf(problems);
        }
    }

    /** Parses and checks one block of container output. */
    static Audit audit(String output, Set<IntentionalErrorFixture> expected) {
        return audit(output, expected, null);
    }

    /** Labels only declared error headers in the archive; other lines and stack traces are preserved. */
    static Audit audit(String output, Set<IntentionalErrorFixture> expected, String classOwner) {
        List<ExternalError> recognised = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        StringBuilder archived = new StringBuilder();
        for (String rawLine : output.split("(?<=\\n)", -1)) {
            String line = ANSI.matcher(rawLine).replaceAll("").stripTrailing();
            Matcher event = EVENT.matcher(line);
            if (event.matches() && "ERROR".equals(event.group("level"))) {
                String logger = event.group("logger");
                String message = event.group("message");
                IntentionalErrorFixture match = expected.stream()
                        .filter(error -> error.matches(logger, Level.ERROR, message))
                        .findFirst().orElse(null);
                if (match == null) problems.add("undeclared container " + line);
                else {
                    recognised.add(new ExternalError(match, logger, message, line));
                    if (classOwner != null) {
                        String label = "*** " + match.kind().title() + ": " + match + ", "
                                + match.kind().attribution() + " " + classOwner + " *** ";
                        int loggerStart = event.start("logger");
                        Matcher escapes = ANSI.matcher(rawLine);
                        while (escapes.find() && escapes.start() <= loggerStart) {
                            loggerStart += escapes.end() - escapes.start();
                        }
                        rawLine = rawLine.substring(0, loggerStart) + label + rawLine.substring(loggerStart);
                    }
                }
            } else if (ERROR_TOKEN.matcher(line).find()) {
                problems.add("unparseable container ERROR: " + line);
            }
            archived.append(rawLine);
        }
        return new Audit(List.copyOf(recognised), List.copyOf(problems), archived.toString());
    }

    private static String safeName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    record ExternalError(IntentionalErrorFixture error, String logger, String message, String originalLine) {
    }

    record Audit(List<ExternalError> recognised, List<String> problems, String archivedOutput) {
    }
}
