package com.cuktech.mobile;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

/** A connection reset wakes readers and rejects callbacks from the old connection. */
final class NotificationBuffer {
    private final int capacity;
    private final ArrayDeque<String> items = new ArrayDeque<>();
    private long generation;

    NotificationBuffer(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
    }

    synchronized long reset() {
        generation++;
        items.clear();
        notifyAll();
        return generation;
    }

    synchronized void offer(long expectedGeneration, String notification) {
        if (generation != expectedGeneration) return;
        if (items.size() == capacity) items.removeFirst();
        items.addLast(notification);
        notifyAll();
    }

    synchronized String poll(long expectedGeneration, int timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (generation == expectedGeneration && items.isEmpty()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return null;
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
        return generation == expectedGeneration ? items.removeFirst() : null;
    }

}
