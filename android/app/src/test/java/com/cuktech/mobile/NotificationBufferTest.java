package com.cuktech.mobile;

import org.junit.Test;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class NotificationBufferTest {
    @Test public void overflowDropsOldestAndPreservesArrivalOrder() throws Exception {
        NotificationBuffer buffer = new NotificationBuffer(3);
        long generation = buffer.reset();
        buffer.offer(generation, "one");
        buffer.offer(generation, "two");
        buffer.offer(generation, "three");
        buffer.offer(generation, "four");
        assertEquals("two", buffer.poll(generation, 0));
        assertEquals("three", buffer.poll(generation, 0));
        assertEquals("four", buffer.poll(generation, 0));
        assertNull(buffer.poll(generation, 0));
    }

    @Test public void resetDiscardsQueuedAndLateNotifications() throws Exception {
        NotificationBuffer buffer = new NotificationBuffer(2);
        long old = buffer.reset();
        buffer.offer(old, "queued");
        long fresh = buffer.reset();
        buffer.offer(old, "late");
        buffer.offer(fresh, "fresh");
        assertEquals("fresh", buffer.poll(fresh, 0));
        assertNull(buffer.poll(fresh, 0));
    }

    @Test public void resetWakesPollAndDoesNotConsumeNextConnectionsData() throws Exception {
        NotificationBuffer buffer = new NotificationBuffer(2);
        long generation = buffer.reset();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> waiting = executor.submit(() -> buffer.poll(generation, 5000));
            try { waiting.get(100, TimeUnit.MILLISECONDS); fail("Poll returned before reset"); }
            catch (TimeoutException expected) { }
            buffer.reset();
            assertNull(waiting.get(1, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }

    @Test public void encodesSignedBytesAsLowercaseUnsignedHex() {
        assertEquals("00017f80abff", BleValueCodec.hex(new byte[] {0, 1, 127, -128, -85, -1}));
        assertEquals("", BleValueCodec.hex(new byte[0]));
    }

    @Test public void delayedOldPollCannotConsumeReconnectedDevicesNotification() throws Exception {
        NotificationBuffer buffer = new NotificationBuffer(2);
        long old = buffer.reset();
        long fresh = buffer.reset();
        buffer.offer(fresh, "fresh");
        assertNull(buffer.poll(old, 0));
        assertEquals("fresh", buffer.poll(fresh, 0));
    }
}
