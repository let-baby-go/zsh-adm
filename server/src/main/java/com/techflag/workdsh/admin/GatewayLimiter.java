package com.techflag.workdsh.admin;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bounds model calls in progress, streaming or not. With virtual threads, thread pools no longer limit
 * concurrency, so this is what protects memory, file descriptors and upstream quotas. A call holds one permit
 * from before the upstream request until its response is fully written or abandoned.
 */
@Component
public class GatewayLimiter {
    private final Semaphore permits;
    private final int limit;

    public GatewayLimiter(@Value("${workdsh.gateway.max-concurrent-requests:2000}") int limit) {
        this.limit = limit;
        this.permits = new Semaphore(limit);
    }

    /** A held slot; closing it more than once releases once. */
    public final class Permit implements AutoCloseable {
        private final AtomicBoolean open = new AtomicBoolean(true);
        @Override public void close() { if (open.compareAndSet(true, false)) permits.release(); }
    }

    /** Never waits: returns null when every slot is in use, so the caller can refuse immediately. */
    public Permit tryAcquire() { return permits.tryAcquire() ? new Permit() : null; }

    public int limit() { return limit; }

    public int inUse() { return limit - permits.availablePermits(); }
}
