package dev.mars.qraft.agent.catalog;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Thread-safe ordered controller selector that prefers the last successful seed. */
public final class ControllerEndpoints {
    private final List<URI> seeds;
    private final AtomicInteger preferredIndex = new AtomicInteger();

    public ControllerEndpoints(List<URI> seeds) {
        Objects.requireNonNull(seeds, "seeds");
        if (seeds.isEmpty()) throw new IllegalArgumentException("At least one controller seed is required");
        this.seeds = List.copyOf(seeds);
        if (this.seeds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Controller seeds must not contain null");
        }
        if (new HashSet<>(this.seeds).size() != this.seeds.size()) {
            throw new IllegalArgumentException("Controller seeds must be unique");
        }
    }

    /** Returns one immutable cycle containing every seed exactly once. */
    public List<URI> cycle() {
        int start = preferredIndex.get();
        List<URI> ordered = new ArrayList<>(seeds.size());
        for (int offset = 0; offset < seeds.size(); offset++) {
            ordered.add(seeds.get((start + offset) % seeds.size()));
        }
        return List.copyOf(ordered);
    }

    public void markSuccessful(URI endpoint) {
        int index = seeds.indexOf(Objects.requireNonNull(endpoint, "endpoint"));
        if (index < 0) throw new IllegalArgumentException("Unknown controller endpoint: " + endpoint);
        preferredIndex.set(index);
    }
}
