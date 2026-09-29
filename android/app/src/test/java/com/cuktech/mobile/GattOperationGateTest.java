package com.cuktech.mobile;

import org.junit.Test;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class GattOperationGateTest {
    @Test public void secondOperationWaitsUntilFirstReleases() throws Exception {
        GattOperationGate gate = new GattOperationGate();
        long generation = gate.reset();
        GattOperationGate.Lease first = gate.acquire(generation, 1000);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<GattOperationGate.Lease> waiting = executor.submit(() -> gate.acquire(generation, 5000));
            try { waiting.get(100, TimeUnit.MILLISECONDS); fail("Concurrent operation acquired slot"); }
            catch (TimeoutException expected) { }
            first.close();
            waiting.get(1, TimeUnit.SECONDS).close();
        } finally { first.close(); executor.shutdownNow(); }
    }

    @Test public void resetRejectsWaitingOperationPromptly() throws Exception {
        GattOperationGate gate = new GattOperationGate();
        long generation = gate.reset();
        GattOperationGate.Lease first = gate.acquire(generation, 1000);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<GattOperationGate.Lease> waiting = executor.submit(() -> gate.acquire(generation, 5000));
            try { waiting.get(100, TimeUnit.MILLISECONDS); fail("Concurrent operation acquired slot"); }
            catch (TimeoutException expected) { }
            gate.reset();
            try { waiting.get(1, TimeUnit.SECONDS); fail("Stale operation survived disconnect"); }
            catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof IllegalStateException); }
        } finally { first.close(); executor.shutdownNow(); }
    }

    @Test public void oldLeaseCannotReleaseNewConnectionsOperation() throws Exception {
        GattOperationGate gate = new GattOperationGate();
        GattOperationGate.Lease old = gate.acquire(gate.reset(), 1000);
        long generation = gate.reset();
        GattOperationGate.Lease fresh = gate.acquire(generation, 1000);
        old.close();
        try {
            gate.acquire(generation, 20);
            fail("Stale release unlocked a fresh operation");
        } catch (TimeoutException expected) { }
        fresh.close();
        gate.acquire(generation, 1000).close();
    }
}
