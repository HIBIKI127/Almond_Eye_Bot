package org.almond_eye_bot.service;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** One bounded background pool per storage instance. No model call holds a memory lock. */
public final class MemorySummaryQueue implements AutoCloseable {
    private static final class Task { volatile boolean cancelled; }
    private static final class Deferred { int depth; final List<Runnable> jobs = new ArrayList<>(); }
    private final ThreadLocal<Deferred> deferred = new ThreadLocal<>();
    private final Map<String, Task> active = new HashMap<>();
    private final Map<String, Long> nextAttempt = new LinkedHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), job -> { var t = new Thread(job,"memory-summary"); t.setDaemon(true); return t; });
    private boolean closed;
    private int running;

    public final class Scope implements AutoCloseable {
        private final Deferred state;
        private boolean done;
        private Scope(Deferred state) { this.state = state; }
        @Override public void close() {
            if (done) return; done = true;
            if (--state.depth == 0) {
                deferred.remove();
                state.jobs.forEach(Runnable::run);
            }
        }
    }
    public Scope afterResponse() {
        var state = deferred.get();
        if (state == null) { state = new Deferred(); deferred.set(state); }
        state.depth++;
        return new Scope(state);
    }
    public synchronized void request(String key, Consumer<BooleanSupplier> action) {
        long now = System.currentTimeMillis();
        if (closed || active.containsKey(key) || now < nextAttempt.getOrDefault(key,0L)) return;
        if (active.size() >= 130) return;
        nextAttempt.entrySet().removeIf(item -> item.getValue() <= now);
        if (nextAttempt.size() >= 10000) nextAttempt.remove(nextAttempt.keySet().iterator().next());
        var token = new Task(); active.put(key,token); nextAttempt.put(key,now + 60_000);
        Runnable dispatch = () -> {
            try {
                executor.execute(() -> {
                    synchronized (this) { running++; }
                    try { if (valid(key,token)) action.accept(() -> valid(key,token)); }
                    finally { synchronized (this) { running--; active.remove(key,token); notifyAll(); } }
                });
            } catch (RejectedExecutionException full) {
                synchronized (this) { active.remove(key,token); nextAttempt.remove(key); notifyAll(); }
            }
        };
        var scope = deferred.get();
        if (scope == null) dispatch.run(); else scope.jobs.add(dispatch);
    }
    private synchronized boolean valid(String key,Task token) { return !closed && !token.cancelled && active.get(key) == token; }
    public synchronized void invalidate(String key) {
        var task = active.remove(key); if (task != null) task.cancelled = true;
        nextAttempt.remove(key); notifyAll();
    }
    public synchronized void completed(String key) { nextAttempt.remove(key); }
    public synchronized boolean awaitIdle(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!active.isEmpty() || running > 0) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            TimeUnit.NANOSECONDS.timedWait(this,remaining);
        }
        return true;
    }
    @Override public synchronized void close() {
        closed = true; active.values().forEach(task -> task.cancelled = true); active.clear();
        executor.shutdownNow(); notifyAll();
    }
}
