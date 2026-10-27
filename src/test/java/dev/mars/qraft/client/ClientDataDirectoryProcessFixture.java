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

package dev.mars.qraft.client;

import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Test helper executable launched in a separate JVM by {@link ClientDataDirectoryProcessLockTest}. It opens the
 * client data directory named by its first argument, prints {@code LOCKED <node ID>}, and holds the directory
 * until stdin receives a line or closes. The second argument names the calling test. It causes no error: the
 * refusal the test checks happens in the test's own JVM.
 *
 * <p>This class belongs in the test sources because it supplies a subprocess for the test. It has a {@code main}
 * entry point rather than JUnit test methods.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-27
 * @version 1.0
 */
public final class ClientDataDirectoryProcessFixture {
    private ClientDataDirectoryProcessFixture() { }

    public static void main(String[] args) {
        Path directory = Path.of(args[0]).toAbsolutePath().normalize();
        System.exit(IntentionalErrorsHelper.inSubprocess(args[1] + "/owner", () -> hold(directory)));
    }

    private static int hold(Path directory) {
        try (ClientDataDirectory owned = ClientDataDirectory.open(directory)) {
            System.out.println("LOCKED " + owned.nodeId());
            System.out.flush();
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            return 0;
        } catch (Exception failure) {
            System.err.println("OPEN_FAILED directory=" + directory + ": " + failure.getMessage());
            return 1;
        }
    }
}
