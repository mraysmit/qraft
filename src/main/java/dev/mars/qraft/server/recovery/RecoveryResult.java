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

import java.nio.file.Path;
import java.util.Objects;

/**
 * What {@link OfflineRecovery#recover} published: the seed's new server ID and the index and term of the recovery
 * entry in {@code output}. {@link Outcome#PUBLICATION_RECORDED} means an earlier run had already published the
 * output, and this run only recorded that in the claim.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-22
 * @version 1.0
 */
public record RecoveryResult(String newServerId, long recoveryIndex, long recoveryTerm, Path output,
                             Outcome outcome) {
    public RecoveryResult {
        Objects.requireNonNull(newServerId, "newServerId");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(outcome, "outcome");
    }

    /** Whether this run built and published the output, or found it published by an interrupted earlier run. */
    public enum Outcome { RECOVERED, PUBLICATION_RECORDED }
}
