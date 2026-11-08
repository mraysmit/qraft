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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A binding rule's selector (authentication list, decision 2): the subset of Consul's selectors this server reads,
 * clauses {@code value.<name> == "<text>"} and {@code "<text>" in list.<name>} joined by {@code and}. An empty selector
 * takes every login. Anything else is refused when the rule is written, so no login is decided on a misread selector.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class Selector {
    private static final Pattern EQUALS = Pattern.compile("value\\.([A-Za-z0-9_]+)\\s*==\\s*\"([^\"]*)\"");
    private static final Pattern IN = Pattern.compile("\"([^\"]*)\"\\s+in\\s+list\\.([A-Za-z0-9_]+)");
    private static final Pattern AND = Pattern.compile("\\s+and\\s+");

    private record Clause(boolean list, String name, String text) { }

    private final List<Clause> clauses;

    private Selector(List<Clause> clauses) {
        this.clauses = List.copyOf(clauses);
    }

    /** The selector {@code text} writes; refused with {@link IllegalArgumentException} for one this server cannot read. */
    public static Selector parse(String text) {
        String selector = text == null ? "" : text.strip();
        List<Clause> clauses = new ArrayList<>();
        if (selector.isEmpty()) return new Selector(clauses);
        int at = 0;
        while (true) {
            Matcher equals = EQUALS.matcher(selector).region(at, selector.length());
            Matcher in = IN.matcher(selector).region(at, selector.length());
            if (equals.lookingAt()) {
                clauses.add(new Clause(false, equals.group(1), equals.group(2)));
                at = equals.end();
            } else if (in.lookingAt()) {
                clauses.add(new Clause(true, in.group(2), in.group(1)));
                at = in.end();
            } else {
                throw new IllegalArgumentException("A selector is clauses value.<name> == \"<text>\" and \"<text>\" in "
                        + "list.<name>, joined by and: " + selector);
            }
            if (at == selector.length()) return new Selector(clauses);
            Matcher and = AND.matcher(selector).region(at, selector.length());
            if (!and.lookingAt()) {
                throw new IllegalArgumentException("Selector clauses are joined by and: " + selector);
            }
            at = and.end();
        }
    }

    /** Whether the mapped claims of a login satisfy every clause. */
    public boolean matches(Map<String, String> values, Map<String, List<String>> lists) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(lists, "lists");
        for (Clause clause : clauses) {
            boolean holds = clause.list() ? lists.getOrDefault(clause.name(), List.of()).contains(clause.text())
                    : clause.text().equals(values.get(clause.name()));
            if (!holds) return false;
        }
        return true;
    }
}
