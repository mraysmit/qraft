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

package dev.mars.qraft.architecture;

import dev.mars.qraft.runtime.QraftRuntimeApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.constant.ClassDesc;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the final package dependency direction: the client and Raft use only Common; replicated state
 * uses Raft and Common; server uses those three layers; and Common uses no other Qraft layer. Only the
 * runtime entry point may use everything. The compiled classes' constant pools are read with the JDK
 * class-file API, so a
 * reference counts whether it comes from an import, a same-package name, or a signature.
 * Compiled mutation fixtures prove forbidden dependencies are rejected after the package migration.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-04
 * @version 1.0
 */
class PackageDependencyTest {

    /** Package ownership and permitted dependencies in the completed Phase 3 layout. */
    enum Layer { RAFT, STATE, COMMON, CLIENT, SERVER, RUNTIME }

    private static final Map<Layer, Set<Layer>> ALLOWED = Map.of(
            Layer.RAFT, EnumSet.of(Layer.RAFT, Layer.COMMON),
            Layer.STATE, EnumSet.of(Layer.STATE, Layer.RAFT, Layer.COMMON),
            Layer.COMMON, EnumSet.of(Layer.COMMON),
            Layer.CLIENT, EnumSet.of(Layer.CLIENT, Layer.COMMON),
            Layer.SERVER, EnumSet.of(Layer.SERVER, Layer.COMMON, Layer.RAFT, Layer.STATE),
            Layer.RUNTIME, EnumSet.allOf(Layer.class));

    private static final Pattern QRAFT_TYPE = Pattern.compile("dev/mars/qraft/[A-Za-z0-9_$/]+");

    @TempDir
    Path fixtureClasses;

    @Test
    void everyProductionClassBelongsToAFinalPackage() throws IOException {
        Map<String, Set<String>> dependencies = dependencies(productionClasses());
        assertTrue(dependencies.size() > 300, "the scan must find production classes");
        Set<String> unclassified = new TreeSet<>();
        dependencies.forEach((type, used) -> {
            if (layerOf(type).isEmpty()) unclassified.add(type);
            used.stream().filter(name -> layerOf(name).isEmpty()).forEach(unclassified::add);
        });
        assertEquals(Set.of(), unclassified, "all Qraft types must use the final Phase 3 packages");
    }

    @Test
    void productionCodeKeepsThePackageDependencyDirection() throws IOException {
        assertEquals(List.of(), violations(dependencies(productionClasses())));
    }

    @Test
    void detectsCompiledRaftAndStateDependenciesOnHttp() throws IOException {
        writeDependencyFixture("dev.mars.qraft.raft.RaftBoundaryFixture",
                "dev.mars.qraft.server.http.HttpBoundaryFixture");
        writeDependencyFixture("dev.mars.qraft.state.StateBoundaryFixture",
                "dev.mars.qraft.server.http.HttpBoundaryFixture");
        assertEquals(List.of(
                "dev.mars.qraft.raft.RaftBoundaryFixture (RAFT) uses dev.mars.qraft.server.http.HttpBoundaryFixture (SERVER)",
                "dev.mars.qraft.state.StateBoundaryFixture (STATE) uses dev.mars.qraft.server.http.HttpBoundaryFixture (SERVER)"),
                violations(dependencies(fixtureClasses)));
    }

    @Test
    void detectsCompiledRaftDependenciesOnReplicatedState() throws IOException {
        writeDependencyFixture("dev.mars.qraft.raft.RaftBoundaryFixture",
                "dev.mars.qraft.state.StateBoundaryFixture");
        assertEquals(List.of("dev.mars.qraft.raft.RaftBoundaryFixture (RAFT) uses "
                + "dev.mars.qraft.state.StateBoundaryFixture (STATE)"),
                violations(dependencies(fixtureClasses)));
    }

    @Test
    void detectsCompiledClientDependenciesOnServerRaftAndState() throws IOException {
        for (String root : List.of("server", "raft", "state")) {
            writeDependencyFixture("dev.mars.qraft.client." + root + ".ClientBoundaryFixture",
                    "dev.mars.qraft." + root + ".DependencyFixture");
        }
        assertEquals(3, violations(dependencies(fixtureClasses)).size());
    }

    @Test
    void detectsCompiledCommonDependenciesOnApplicationCode() throws IOException {
        writeDependencyFixture("dev.mars.qraft.common.CommonBoundaryFixture",
                "dev.mars.qraft.client.ClientBoundaryFixture");
        assertEquals(List.of("dev.mars.qraft.common.CommonBoundaryFixture (COMMON) uses "
                + "dev.mars.qraft.client.ClientBoundaryFixture (CLIENT)"),
                violations(dependencies(fixtureClasses)));
    }

    @Test
    void acceptsCompiledStateRaftAndClientDependenciesOnSharedSupport() throws IOException {
        writeDependencyFixture("dev.mars.qraft.state.StateBoundaryFixture",
                "dev.mars.qraft.raft.api.ReplicatedCommand");
        writeDependencyFixture("dev.mars.qraft.raft.RaftBoundaryFixture",
                "dev.mars.qraft.common.async.Future");
        writeDependencyFixture("dev.mars.qraft.client.ClientBoundaryFixture",
                "dev.mars.qraft.common.ServiceDefinition");
        Map<String, Set<String>> scanned = dependencies(fixtureClasses);
        assertEquals(3, scanned.size());
        assertEquals(List.of(), violations(scanned));
    }

    @Test
    void onlyRuntimeMayUseTheEntryPoint() throws IOException {
        writeDependencyFixture("dev.mars.qraft.server.ServerBoundaryFixture",
                "dev.mars.qraft.runtime.QraftRuntimeApplication");
        assertEquals(List.of("dev.mars.qraft.server.ServerBoundaryFixture (SERVER) uses "
                + "dev.mars.qraft.runtime.QraftRuntimeApplication (RUNTIME)"),
                violations(dependencies(fixtureClasses)));
    }

    @Test
    void classifiesNestedTypesAndRequiresAPackageBoundary() {
        assertEquals(Optional.of(Layer.COMMON), layerOf("dev.mars.qraft.common.Node$Builder"));
        assertEquals(Optional.of(Layer.RAFT), layerOf("dev.mars.qraft.raft.storage.Store"));
        assertEquals(Optional.of(Layer.STATE), layerOf("dev.mars.qraft.state.catalog.ServiceCatalog$Snapshot"));
        for (String root : List.of("common", "client", "server", "raft", "state", "runtime")) {
            assertEquals(Optional.empty(), layerOf("dev.mars.qraft." + root + "like.Other"));
        }
        assertEquals(Optional.empty(), layerOf("dev.mars.qraft.controller.RaftNode"));
        assertEquals(Optional.empty(), layerOf("dev.mars.qraft.agent.AgentInfo"));
    }

    static Optional<Layer> layerOf(String type) {
        int nested = type.indexOf('$');
        String topLevel = nested < 0 ? type : type.substring(0, nested);
        int lastDot = topLevel.lastIndexOf('.');
        String packageName = lastDot < 0 ? "" : topLevel.substring(0, lastDot);
        for (Layer layer : Layer.values()) {
            if (within(packageName, "dev.mars.qraft." + layer.name().toLowerCase(java.util.Locale.ROOT))) {
                return Optional.of(layer);
            }
        }
        return Optional.empty();
    }

    static List<String> violations(Map<String, Set<String>> dependencies) {
        List<String> violations = new ArrayList<>();
        new TreeMap<>(dependencies).forEach((type, used) -> layerOf(type).ifPresent(from ->
                new TreeSet<>(used).forEach(name -> layerOf(name)
                        .filter(to -> !ALLOWED.get(from).contains(to))
                        .ifPresent(to -> violations.add(type + " (" + from + ") uses " + name + " (" + to + ")")))));
        return violations;
    }


    /** Writes a bytecode fixture with a field dependency, without changing production sources or classes. */
    private void writeDependencyFixture(String owner, String dependency) throws IOException {
        byte[] bytes = ClassFile.of().build(ClassDesc.of(owner), builder ->
                builder.withField("dependency", ClassDesc.of(dependency), ClassFile.ACC_PRIVATE));
        Path file = fixtureClasses.resolve(owner.replace('.', '/') + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    /** Every Qraft type that each compiled class names in its constant pool, keyed by the class's own name. */
    static Map<String, Set<String>> dependencies(Path classesDirectory) throws IOException {
        Map<String, Set<String>> dependencies = new TreeMap<>();
        try (Stream<Path> files = Files.walk(classesDirectory)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                ClassModel model = ClassFile.of().parse(file);
                String type = binaryName(model.thisClass().asInternalName());
                Set<String> used = new TreeSet<>();
                for (PoolEntry entry : model.constantPool()) {
                    if (entry instanceof ClassEntry classEntry) {
                        collectQraftTypes(classEntry.asInternalName(), used);
                    } else if (entry instanceof Utf8Entry text) {
                        collectQraftTypes(text.stringValue(), used);
                    }
                }
                used.remove(type);
                dependencies.put(type, used);
            }
        }
        return dependencies;
    }

    private static void collectQraftTypes(String text, Set<String> used) {
        Matcher matcher = QRAFT_TYPE.matcher(text);
        while (matcher.find()) used.add(binaryName(matcher.group()));
    }

    private static String binaryName(String internalName) {
        return internalName.replace('/', '.');
    }

    private static boolean within(String packageName, String root) {
        return packageName.equals(root) || packageName.startsWith(root + ".");
    }

    private static Path productionClasses() {
        try {
            return Path.of(QraftRuntimeApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException error) {
            throw new IllegalStateException("the production classes' location is not a file path", error);
        }
    }
}
