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
    private static final Pattern UNCAUGHT_EXCEPTION = Pattern.compile(
            "(?:^|\\s)Exception in thread \"[^\"]+\" (?<exception>.+)$");
    private static final Map<String, String> PREVIOUS_OUTPUT = new HashMap<>();
    private static final Map<String, List<ExternalError>> PREVIOUS_ERRORS = new HashMap<>();

    private static String owner;
    private static Set<IntentionalErrorFixture> declared = Set.of();
    private static final Map<String, StringBuilder> captured = new LinkedHashMap<>();
    private static final Map<String, String> sourceNames = new HashMap<>();
    private static final Map<String, List<ExternalError>> precedingErrors = new HashMap<>();

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
            sourceNames.clear();
            precedingErrors.clear();
        }
    }

    /** Captures only output not already drained from this container or detached process. */
    public static void capture(String sourceId, String sourceName, String completeOutput) {
        synchronized (LOCK) {
            if (owner == null || completeOutput == null) return;
            String previous = PREVIOUS_OUTPUT.getOrDefault(sourceId, "");
            boolean continued = completeOutput.startsWith(previous);
            String addition = continued
                    ? completeOutput.substring(previous.length()) : completeOutput;
            if (!continued) {
                PREVIOUS_ERRORS.remove(sourceId);
                precedingErrors.remove(sourceId);
            }
            precedingErrors.putIfAbsent(sourceId, PREVIOUS_ERRORS.getOrDefault(sourceId, List.of()));
            PREVIOUS_OUTPUT.put(sourceId, completeOutput);
            sourceNames.put(sourceId, safeName(sourceName));
            StringBuilder destination = captured.computeIfAbsent(
                    sourceId, ignored -> new StringBuilder());
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
            Map<String, StringBuilder> archives = new LinkedHashMap<>();
            Map<String, List<ExternalError>> history = new HashMap<>();
            Path classDirectory = logRoot.resolve(owner);
            try {
                Files.createDirectories(classDirectory);
                for (Map.Entry<String, StringBuilder> entry : captured.entrySet()) {
                    String output = entry.getValue().toString();
                    String sourceName = sourceNames.get(entry.getKey());
                    List<ExternalError> previous = precedingErrors.getOrDefault(entry.getKey(), List.of());
                    Audit audit = audit(output, declared, owner, previous);
                    archives.computeIfAbsent(sourceName, ignored -> new StringBuilder()).append(audit.archivedOutput());
                    List<ExternalError> sourceHistory = new ArrayList<>(previous);
                    sourceHistory.addAll(audit.recognised());
                    history.put(entry.getKey(), List.copyOf(sourceHistory));
                    recognised.addAll(audit.recognised());
                    problems.addAll(audit.problems().stream()
                            .map(problem -> sourceName + ": " + problem).toList());
                    audit.recognised().forEach(event -> observed.add(event.error()));
                }
                for (Map.Entry<String, StringBuilder> archive : archives.entrySet()) {
                    Files.writeString(classDirectory.resolve(archive.getKey() + ".log"), archive.getValue(),
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                }
                PREVIOUS_ERRORS.putAll(history);
                observed.forEach(IntentionalErrorsHelper::expect);
                recognised.forEach(event -> LoggerFactory.getLogger(event.logger()).error(event.message()));
            } catch (IOException error) {
                problems.add("could not archive Docker logs for " + owner + ": " + error.getMessage());
            } finally {
                owner = null;
                declared = Set.of();
                captured.clear();
                sourceNames.clear();
                precedingErrors.clear();
            }
            return List.copyOf(problems);
        }
    }

    /** Parses and checks one block of container output. */
    static Audit audit(String output, Set<IntentionalErrorFixture> expected) {
        return audit(output, expected, null);
    }

    /** Labels declared errors and their matching uncaught rethrows; other lines and stack traces are preserved. */
    static Audit audit(String output, Set<IntentionalErrorFixture> expected, String classOwner) {
        return audit(output, expected, classOwner, List.of());
    }

    private static Audit audit(String output, Set<IntentionalErrorFixture> expected, String classOwner,
                               List<ExternalError> previous) {
        List<ExternalError> recognised = new ArrayList<>();
        // Docker can merge stderr ahead of stdout. Discover matching ERROR events before auditing rethrows.
        for (String rawLine : output.split("(?<=\\n)", -1)) {
            String line = ANSI.matcher(rawLine).replaceAll("").stripTrailing();
            Matcher event = EVENT.matcher(line);
            if (!event.matches() || !"ERROR".equals(event.group("level"))) continue;
            IntentionalErrorFixture match = matchDeclaredError(event, expected);
            if (match != null) {
                recognised.add(new ExternalError(match, event.group("logger"), event.group("message"), line));
            }
        }
        List<ExternalError> rethrowCandidates = new ArrayList<>(recognised);
        previous.stream().filter(error -> expected.contains(error.error())).forEach(rethrowCandidates::add);
        List<String> problems = new ArrayList<>();
        StringBuilder archived = new StringBuilder();
        for (String rawLine : output.split("(?<=\\n)", -1)) {
            String line = ANSI.matcher(rawLine).replaceAll("").stripTrailing();
            Matcher event = EVENT.matcher(line);
            if (event.matches()) {
                if ("ERROR".equals(event.group("level"))) {
                    IntentionalErrorFixture match = matchDeclaredError(event, expected);
                    if (match == null) problems.add("undeclared container " + line);
                    else rawLine = labelLine(rawLine, event.start("logger"), match, classOwner);
                }
            } else if (line.contains("Exception in thread")) {
                Matcher uncaught = UNCAUGHT_EXCEPTION.matcher(line);
                if (!uncaught.find()) {
                    problems.add("unparseable container uncaught exception: " + line);
                } else {
                    ExternalError match = rethrowCandidates.stream()
                            .filter(reported -> reported.error().matchesUncaughtException(
                                    uncaught.group("exception"), reported.message()))
                            .findFirst().orElse(null);
                    if (match == null) problems.add("undeclared container uncaught exception: " + line);
                    else rawLine = labelLine(rawLine, uncaught.start("exception"), match.error(), classOwner);
                }
            } else if (ERROR_TOKEN.matcher(line).find()) {
                problems.add("unparseable container ERROR: " + line);
            }
            archived.append(rawLine);
        }
        return new Audit(List.copyOf(recognised), List.copyOf(problems), archived.toString());
    }

    private static IntentionalErrorFixture matchDeclaredError(Matcher event, Set<IntentionalErrorFixture> expected) {
        return expected.stream()
                .filter(error -> error.matches(event.group("logger"), Level.ERROR, event.group("message")))
                .findFirst().orElse(null);
    }

    private static String labelLine(String rawLine, int offset, IntentionalErrorFixture error, String classOwner) {
        if (classOwner == null) return rawLine;
        String label = "*** " + error.kind().title() + ": " + error + ", "
                + error.kind().attribution() + " " + classOwner + " *** ";
        Matcher escapes = ANSI.matcher(rawLine);
        while (escapes.find() && escapes.start() <= offset) {
            offset += escapes.end() - escapes.start();
        }
        return rawLine.substring(0, offset) + label + rawLine.substring(offset);
    }

    private static String safeName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    record ExternalError(IntentionalErrorFixture error, String logger, String message, String originalLine) {
    }

    record Audit(List<ExternalError> recognised, List<String> problems, String archivedOutput) {
    }
}
