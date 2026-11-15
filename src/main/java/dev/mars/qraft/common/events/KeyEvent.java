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

package dev.mars.qraft.common.events;

/**
 * An event about keys of the key/value store, such as {@code key.set} or {@code key.tree_deleted}, so an
 * {@link EventFilter} can select one key's events (UI-6 Key/Value list, decision 8).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-15
 * @version 1.0
 */
public interface KeyEvent extends QraftEvent {
    /** Whether the event is about {@code key}: it names the key, or deleted a tree that held it. */
    boolean concerns(String key);
}
