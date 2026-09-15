/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.mars.qraft.controller.raft;

import dev.mars.qraft.controller.testsupport.RemediationTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RemediationTest(phase = "6-architecture", scenarioPrefix = "RAFT-PERSISTENCE-ARCH")
class RaftPersistenceArchitectureTest {
    private static final Map<String, String[]> GUARDED_GATEWAYS = new LinkedHashMap<>();

    static {
        GUARDED_GATEWAYS.put("private Future<Void> persistMetadata(",
                new String[]{".updateMetadata("});
        GUARDED_GATEWAYS.put("private Future<Void> persistLogEntry(",
                new String[]{".appendEntries(", ".sync("});
        GUARDED_GATEWAYS.put("private Future<Void> persistAppendEntries(",
                new String[]{".truncateSuffix(", ".appendEntries(", ".sync("});
        GUARDED_GATEWAYS.put("private Future<LocalSnapshotDecision> preparePublishAndCompactLocalSnapshot(",
                new String[]{".saveAtomically(", ".truncatePrefix("});
        GUARDED_GATEWAYS.put("private Future<InstalledSnapshotPlan> persistInstalledSnapshot(",
                new String[]{".saveAtomically(", ".truncatePrefix("});
    }

    @Test
    void everyRaftStorageMutationIsConfinedToAnOwnedTransitionGateway() throws IOException {
        String source = Files.readString(findRaftNodeSource());
        String unclaimed = source;

        for (Map.Entry<String, String[]> gateway : GUARDED_GATEWAYS.entrySet()) {
            String body = methodBody(source, gateway.getKey());
            assertTrue(body.contains("transitionSequencer.assertActiveTransition();"),
                    gateway.getKey() + " must enforce active transition ownership");
            for (String mutation : gateway.getValue()) {
                assertTrue(body.contains(mutation),
                        gateway.getKey() + " no longer owns expected mutation " + mutation);
            }
            unclaimed = unclaimed.replace(body, "");
        }

        for (String[] mutations : GUARDED_GATEWAYS.values()) {
            for (String mutation : mutations) {
                assertFalse(unclaimed.contains(mutation),
                        "Raft storage mutation bypasses a guarded transition gateway: " + mutation);
            }
        }
    }

    private static Path findRaftNodeSource() {
        Path working = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path fromRoot = working.resolve(
                "qraft-controller/src/main/java/dev/mars/qraft/controller/raft/RaftNode.java");
        if (Files.exists(fromRoot)) return fromRoot;
        Path fromModule = working.resolve(
                "src/main/java/dev/mars/qraft/controller/raft/RaftNode.java");
        if (Files.exists(fromModule)) return fromModule;
        throw new AssertionError("Cannot locate RaftNode.java from " + working);
    }

    private static String methodBody(String source, String declaration) {
        int method = source.indexOf(declaration);
        if (method < 0) throw new AssertionError("Missing persistence gateway " + declaration);
        int start = source.indexOf('{', method);
        if (start < 0) throw new AssertionError("Missing body for " + declaration);
        int depth = 0;
        for (int index = start; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '{') depth++;
            if (current == '}' && --depth == 0) return source.substring(start, index + 1);
        }
        throw new AssertionError("Unterminated body for " + declaration);
    }
}
