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

package dev.mars.qraft.raft.storage;

import dev.mars.raftlog.storage.FileRaftStorage;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static dev.mars.qraft.testing.fault.IntentionalErrorFixture.WAL_DIRECTORY_ALREADY_LOCKED;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * How {@link RaftStorageFactory} reports a storage directory it cannot open.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-12
 * @version 1.0
 */
class RaftStorageFactoryTest {

    @TempDir
    Path directory;

    @Test
    void aFailedOpenGivesACallbackTheStorageFailureItself() throws Exception {
        IntentionalErrorsHelper.expect(WAL_DIRECTORY_ALREADY_LOCKED, 1);
        RaftStorageFactory.DurableStorage owner = RaftStorageFactory.createDurable(directory, true)
                .get(10, TimeUnit.SECONDS);
        try {
            Throwable failure = RaftStorageFactory.createDurable(directory, true)
                    .handle((storage, error) -> error)
                    .get(10, TimeUnit.SECONDS);

            assertInstanceOf(FileRaftStorage.StorageException.class, failure,
                    "a callback on the returned future sees the failure, not a wrapper around it");
        } finally {
            owner.wal().closeAsync().join();
            owner.snapshots().close();
        }
    }

    @Test
    void aFailedOpenLeavesTheDirectoryToItsOwner() throws Exception {
        IntentionalErrorsHelper.expect(WAL_DIRECTORY_ALREADY_LOCKED, 1);
        RaftStorageFactory.DurableStorage owner = RaftStorageFactory.createDurable(directory, true)
                .get(10, TimeUnit.SECONDS);
        try {
            RaftStorageFactory.createDurable(directory, true)
                    .handle((storage, error) -> error)
                    .get(10, TimeUnit.SECONDS);
        } finally {
            owner.wal().closeAsync().join();
            owner.snapshots().close();
        }

        RaftStorageFactory.DurableStorage reopened = RaftStorageFactory.createDurable(directory, true)
                .get(10, TimeUnit.SECONDS);
        reopened.wal().closeAsync().join();
        reopened.snapshots().close();
    }
}
