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

package dev.mars.qraft.server.recovery;

/**
 * Each durable step {@link OfflineRecovery#recover} completes, in order (Step 7 contract). A crash test halts the
 * JVM at one of them and proves that the next run completes or refuses without change. Production passes a
 * listener that does nothing.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-23
 * @version 1.0
 */
enum RecoveryCheckpoint {
    /** The claim beside the source exists, before anything is copied. */
    CLAIM_CREATED,
    /** Step 2: the staging copy is complete and verified. */
    SOURCE_COPIED,
    /** Step 3: the ordinary checks accepted the copy, and its examining node has stopped. */
    EXAMINED,
    /** Step 4: the recovery entry is synced, before the metadata moves to its term. */
    RECOVERY_ENTRY_SYNCED,
    /** Step 5: the metadata holds the recovery term with no vote, and the copy's storage is closed. */
    METADATA_UPDATED,
    /** Step 6: the copy holds its new server ID. */
    SERVER_ID_REPLACED,
    /** The claim records the new server ID and the recovery entry, before publication. */
    CLAIM_PREPARED,
    /** Step 7: the output exists, before the claim records its publication. */
    PUBLISHED,
    /** The claim records the publication; the run is complete. */
    CLAIM_PUBLISHED
}
