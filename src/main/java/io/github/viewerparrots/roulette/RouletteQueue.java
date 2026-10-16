package io.github.viewerparrots.roulette;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Single active spin and a bounded FIFO, keyed by the owning viewer. */
public final class RouletteQueue<T> {
    public record Entry<T>(String key, T value) { }
    private final ArrayDeque<Entry<T>> waiting = new ArrayDeque<>();
    private final Set<String> pending = new HashSet<>();
    private Entry<T> active;

    public boolean offer(String key, T value, int waitingCapacity) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        if (waitingCapacity < 1) throw new IllegalArgumentException("Queue capacity must be positive");
        if (pending.contains(key) || waiting.size() >= waitingCapacity) return false;
        waiting.addLast(new Entry<>(key, value));
        pending.add(key);
        return true;
    }
    public boolean contains(String key) { return pending.contains(key); }
    public Optional<Entry<T>> startNext() {
        if (active != null) return Optional.empty();
        active = waiting.pollFirst();
        return Optional.ofNullable(active);
    }
    public void complete() {
        if (active != null) pending.remove(active.key());
        active = null;
    }
    public Optional<Entry<T>> remove(String key) {
        Entry<T> removed = null;
        if (active != null && active.key().equals(key)) {
            removed = active;
            active = null;
        } else {
            for (var iterator = waiting.iterator(); iterator.hasNext();) {
                Entry<T> entry = iterator.next();
                if (entry.key().equals(key)) { removed = entry; iterator.remove(); break; }
            }
        }
        pending.remove(key);
        return Optional.ofNullable(removed);
    }
    public int waitingCount() { return waiting.size(); }
    public List<Entry<T>> clear() {
        List<Entry<T>> removed = new ArrayList<>(waiting);
        if (active != null) removed.add(active);
        waiting.clear(); pending.clear(); active = null;
        return List.copyOf(removed);
    }
}
