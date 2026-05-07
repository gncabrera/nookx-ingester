package com.nookx.ingester.pipeline.runner;

import com.nookx.ingester.domain.enumeration.JobScopeType;
import com.nookx.ingester.domain.enumeration.JobStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class PipelineLockRegistry {

    private final ConcurrentMap<String, AtomicBoolean> locks = new ConcurrentHashMap<>();

    public boolean tryAcquire(final JobStage stage, final JobScopeType scopeType, final String scopeCode) {
        final AtomicBoolean lock = locks.computeIfAbsent(keyOf(stage, scopeType, scopeCode), k -> new AtomicBoolean(false));
        return lock.compareAndSet(false, true);
    }

    public void release(final JobStage stage, final JobScopeType scopeType, final String scopeCode) {
        final AtomicBoolean lock = locks.get(keyOf(stage, scopeType, scopeCode));
        if (lock != null) {
            lock.set(false);
        }
    }

    public boolean isHeld(final JobStage stage, final JobScopeType scopeType, final String scopeCode) {
        final AtomicBoolean lock = locks.get(keyOf(stage, scopeType, scopeCode));
        return lock != null && lock.get();
    }

    private static String keyOf(final JobStage stage, final JobScopeType scopeType, final String scopeCode) {
        return stage.name() + ":" + scopeType.name() + ":" + (scopeCode == null ? "" : scopeCode);
    }
}
