package com.github.lechandonga.monoreporeleasetrainjava.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次整批发布请求及其推进状态。
 */
public final class ReleaseBatch {

    public enum State {
        PLANNED,
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    private final String id;
    private final Instant createdAt = Instant.now();
    private final List<ReleaseItem> items = new ArrayList<>();
    private State state = State.PLANNED;
    private Instant updatedAt = createdAt;

    public ReleaseBatch(String id) {
        this.id = id;
    }

    public String id() { return id; }
    public Instant createdAt() { return createdAt; }
    public List<ReleaseItem> items() { return items; }
    public State state() { return state; }
    public void state(State s) {
        this.state = s;
        this.updatedAt = Instant.now();
    }
    public Instant updatedAt() { return updatedAt; }

    public ReleaseItem item(String packageName) {
        return items.stream()
                .filter(i -> i.packageName().equals(packageName))
                .findFirst().orElse(null);
    }
}
