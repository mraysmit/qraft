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

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.Utf8Entry;
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
 * Tests that production code keeps the dependency direction the Maven modules enforced before they were
 * merged into one build: the client never uses server, Raft, or replicated-state code; replicated state
 * uses only the Raft contracts; shared types use nothing else of Qraft's; and only the runtime entry point
 * may use everything. The compiled classes' constant pools are read with the JDK class-file API, so a
 * reference counts whether it comes from an import, a same-package name, or a signature.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-04
 * @version 1.0
 */
class PackageDependencyTest {

    /** The former Maven modules, as layers of the one build. */
    enum Layer { ENGINE, STATE, CORE, CLIENT, SERVER, RUNTIME }

    /** Each layer and the layers it may use, matching the former module dependencies. */
    private static final Map<Layer, Set<Layer>> ALLOWED = Map.of(
            Layer.ENGINE, EnumSet.of(Layer.ENGINE),
            Layer.STATE, EnumSet.of(Layer.STATE, Layer.ENGINE),
            Layer.CORE, EnumSet.of(Layer.CORE),
            Layer.CLIENT, EnumSet.of(Layer.CLIENT, Layer.CORE),
            Layer.SERVER, EnumSet.of(Layer.SERVER, Layer.CORE, Layer.ENGINE, Layer.STATE),
            Layer.RUNTIME, EnumSet.allOf(Layer.class));

    /**
     * Shared types in {@code dev.mars.qraft.agent}, a package two modules used. The rest of that package is
     * client code. Phase 3 of the refactoring gives each its own package.
     */
    private static final Set<String> SHARED_AGENT_TYPES = Set.of(
            "AgentCapabilities", "AgentInfo", "AgentNetworkInfo", "AgentStatus", "AgentSystemInfo");

    private static final Pattern QRAFT_TYPE = Pattern.compile("dev/mars/qraft/[A-Za-z0-9_$/]+");

    @Test
    void everyProductionClassBelongsToALayer() throws IOException {
        Map<String, Set<String>> dependencies = dependencies(productionClasses());

        assertTrue(dependencies.size() > 300,
                "the scan must find the production classes, found " + dependencies.size());
        Set<String> unclassified = new TreeSet<>();
        dependencies.forEach((type, used) -> {
            if (layerOf(type).isEmpty()) unclassified.add(type);
            used.stream().filter(name -> layerOf(name).isEmpty()).forEach(unclassified::add);
        });
        assertEquals(Set.of(), unclassified, "assign these Qraft types to a layer");
    }

    @Test
    void productionCodeKeepsTheModuleDependencyDirection() throws IOException {
        assertEquals(List.of(), violations(dependencies(productionClasses())));
    }

    @Test
    void reportsADependencyAgainstTheDirectionAndAcceptsOneWithIt() {
        assertEquals(List.of("dev.mars.qraft.agent.QraftAgent (CLIENT) uses "
                        + "dev.mars.qraft.controller.raft.RaftNode (SERVER)"),
                violations(Map.of("dev.mars.qraft.agent.QraftAgent",
                        Set.of("dev.mars.qraft.controller.raft.RaftNode"))));
        assertEquals(List.of(), violations(Map.of("dev.mars.qraft.agent.QraftAgent",
                Set.of("dev.mars.qraft.catalog.ServiceDefinition", "dev.mars.qraft.agent.AgentInfo"))));
        assertEquals(List.of("dev.mars.qraft.catalog.ServiceDefinition (CORE) uses "
                        + "dev.mars.qraft.catalog.ServiceCatalog$Snapshot (STATE)"),
                violations(Map.of("dev.mars.qraft.catalog.ServiceDefinition",
                        Set.of("dev.mars.qraft.catalog.ServiceCatalog$Snapshot"))));
    }

    @Test
    void classifiesTheSharedPackagesTypeByType() {
        assertEquals(Optional.of(Layer.CORE), layerOf("dev.mars.qraft.agent.AgentInfo$Builder"));
        assertEquals(Optional.of(Layer.CLIENT), layerOf("dev.mars.qraft.agent.QraftAgent"));
        assertEquals(Optional.of(Layer.CLIENT), layerOf("dev.mars.qraft.agent.health.HttpCheck"));
        assertEquals(Optional.of(Layer.CORE), layerOf("dev.mars.qraft.catalog.ServiceDefinition"));
        assertEquals(Optional.of(Layer.STATE), layerOf("dev.mars.qraft.catalog.ServiceInstance"));
        assertEquals(Optional.empty(), layerOf("dev.mars.qraft.unknown.Type"));
    }

    static Optional<Layer> layerOf(String type) {
        int nested = type.indexOf('$');
        String topLevel = nested < 0 ? type : type.substring(0, nested);
        int lastDot = topLevel.lastIndexOf('.');
        String packageName = lastDot < 0 ? "" : topLevel.substring(0, lastDot);
        String simpleName = topLevel.substring(lastDot + 1);

        if (within(packageName, "dev.mars.qraft.raft.api")) return Optional.of(Layer.ENGINE);
        if (within(packageName, "dev.mars.qraft.distributedstate")) return Optional.of(Layer.STATE);
        if (packageName.equals("dev.mars.qraft.catalog")) {
            return Optional.of(simpleName.equals("ServiceDefinition") ? Layer.CORE : Layer.STATE);
        }
        if (within(packageName, "dev.mars.qraft.concurrent") || within(packageName, "dev.mars.qraft.config")) {
            return Optional.of(Layer.CORE);
        }
        if (packageName.equals("dev.mars.qraft.agent")) {
            return Optional.of(SHARED_AGENT_TYPES.contains(simpleName) ? Layer.CORE : Layer.CLIENT);
        }
        if (within(packageName, "dev.mars.qraft.agent")) return Optional.of(Layer.CLIENT);
        if (within(packageName, "dev.mars.qraft.controller")) return Optional.of(Layer.SERVER);
        if (within(packageName, "dev.mars.qraft.runtime")) return Optional.of(Layer.RUNTIME);
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
