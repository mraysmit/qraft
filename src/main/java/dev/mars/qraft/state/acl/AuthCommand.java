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

package dev.mars.qraft.state.acl;

import dev.mars.qraft.raft.RaftCommand;

import java.util.Objects;

/**
 * A replicated change to the auth methods, the binding rules or the login tokens (authentication list, Phases 1 and
 * 2). A login carries the token the proposer minted, so every replica stores the same one.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public sealed interface AuthCommand extends RaftCommand {
    record CreateMethod(AuthMethod method) implements AuthCommand {
        private static final long serialVersionUID = 1L;

        public CreateMethod {
            Objects.requireNonNull(method, "method");
        }
    }

    record UpdateMethod(AuthMethod method) implements AuthCommand {
        private static final long serialVersionUID = 1L;

        public UpdateMethod {
            Objects.requireNonNull(method, "method");
        }
    }

    record DeleteMethod(String name) implements AuthCommand {
        private static final long serialVersionUID = 1L;

        public DeleteMethod {
            Objects.requireNonNull(name, "name");
        }
    }

    record CreateBindingRule(BindingRule rule) implements AuthCommand {
        private static final long serialVersionUID = 1L;

        public CreateBindingRule {
            Objects.requireNonNull(rule, "rule");
        }
    }

    record UpdateBindingRule(BindingRule rule) implements AuthCommand {
        private static final long serialVersionUID = 1L;

        public UpdateBindingRule {
            Objects.requireNonNull(rule, "rule");
        }
    }

    record DeleteBindingRule(String id) implements AuthCommand {
        private static final long serialVersionUID = 1L;

        public DeleteBindingRule {
            Objects.requireNonNull(id, "id");
        }
    }

    record Login(AclToken token, String method) implements AuthCommand {
        private static final long serialVersionUID = 1L;

        public Login {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(method, "method");
        }
    }

    record Logout(String accessorId) implements AuthCommand {
        private static final long serialVersionUID = 1L;

        public Logout {
            Objects.requireNonNull(accessorId, "accessorId");
        }
    }

    static AuthCommand createMethod(AuthMethod method) {
        return new CreateMethod(method);
    }

    static AuthCommand updateMethod(AuthMethod method) {
        return new UpdateMethod(method);
    }

    static AuthCommand deleteMethod(String name) {
        return new DeleteMethod(name);
    }

    static AuthCommand createBindingRule(BindingRule rule) {
        return new CreateBindingRule(rule);
    }

    static AuthCommand updateBindingRule(BindingRule rule) {
        return new UpdateBindingRule(rule);
    }

    static AuthCommand deleteBindingRule(String id) {
        return new DeleteBindingRule(id);
    }

    static AuthCommand login(AclToken token, String method) {
        return new Login(token, method);
    }

    static AuthCommand logout(String accessorId) {
        return new Logout(accessorId);
    }
}
