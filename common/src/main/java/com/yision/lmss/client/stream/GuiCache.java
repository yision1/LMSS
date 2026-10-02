package com.yision.lmss.client.stream;

import java.util.LinkedHashMap;
import java.util.Map;

public final class GuiCache<K, V> {
    public static final long TTL_NANOS = 60_000_000_000L;
    private long ttlNanos = TTL_NANOS;
    private final Map<K, Entry<V>> entries = new LinkedHashMap<>();
    private record Entry<V>(V value, long closedAt) {}

    public void setLifetimeSeconds(int seconds) {
        if (seconds < 0 || seconds > 3600) {
            throw new IllegalArgumentException("Invalid cache lifetime");
        }
        ttlNanos = seconds * 1_000_000_000L;
        clear();
    }

    public void put(K key, V value, long now) {
        if (ttlNanos == 0) {
            return;
        }
        expire(now);
        entries.remove(key);
        if (entries.size() == 32) {
            entries.remove(entries.keySet().iterator().next());
        }
        entries.put(key, new Entry<>(value, now));
    }

    public V take(K key, long now) {
        expire(now);
        Entry<V> entry = entries.remove(key);
        return entry == null ? null : entry.value();
    }

    public void expire(long now) {
        entries.values().removeIf(entry -> now - entry.closedAt() >= ttlNanos);
    }

    public void clear() {
        entries.clear();
    }
}
