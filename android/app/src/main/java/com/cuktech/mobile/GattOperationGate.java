package com.cuktech.mobile;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

/** Single operation slot, invalidated atomically whenever the GATT connection changes. */
final class GattOperationGate {
    private long generation;
    private Lease owner;

    synchronized long reset() {
        generation++;
        owner = null;
        notifyAll();
        return generation;
    }

    synchronized Lease acquire(long expectedGeneration, int timeoutMs)
            throws InterruptedException, TimeoutException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            if (expectedGeneration != generation) {
                throw new IllegalStateException("Bluetooth connection changed while waiting for a GATT operation");
            }
            if (owner == null) {
                owner = new Lease();
                return owner;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException("Timed out waiting for the GATT operation slot");
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
    }

    final class Lease implements AutoCloseable {
        @Override public void close() {
            synchronized (GattOperationGate.this) {
                if (owner == this) {
                    owner = null;
                    GattOperationGate.this.notifyAll();
                }
            }
        }
    }
}
