package com.nookx.ingester.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public class JobMetrics {

    private final ConcurrentMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public long inc(final String name) {
        return inc(name, 1L);
    }

    public long inc(final String name, final long delta) {
        return counters.computeIfAbsent(name, k -> new AtomicLong(0L)).addAndGet(delta);
    }

    public long get(final String name) {
        final AtomicLong counter = counters.get(name);
        return counter == null ? 0L : counter.get();
    }

    public Map<String, Long> snapshot() {
        final Map<String, Long> snapshot = new LinkedHashMap<>();
        for (final Map.Entry<String, AtomicLong> entry : counters.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().get());
        }
        return snapshot;
    }

    public ObjectNode toJson(final ObjectMapper objectMapper) {
        final ObjectNode node = objectMapper.createObjectNode();
        for (final Map.Entry<String, Long> entry : snapshot().entrySet()) {
            node.put(entry.getKey(), entry.getValue());
        }
        return node;
    }
}
