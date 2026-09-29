package com.cuktech.mobile;

final class RuntimeGeneration {
    private long generation;
    private boolean active;

    synchronized long requestStart() {
        if (active) return 0;
        active = true;
        return ++generation;
    }

    synchronized void requestStop() {
        active = false;
        generation++;
    }

    synchronized boolean isCurrent(long token) {
        return active && token > 0 && generation == token;
    }

    synchronized boolean isActive() { return active; }
}
