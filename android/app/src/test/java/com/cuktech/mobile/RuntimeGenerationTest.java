package com.cuktech.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class RuntimeGenerationTest {
    @Test public void repeatedActivityStartsDoNotInitializeAgain() {
        RuntimeGeneration state = new RuntimeGeneration();
        long first = state.requestStart();
        assertTrue(first > 0);
        assertEquals(0, state.requestStart());
        assertTrue(state.isCurrent(first));
    }

    @Test public void stopDiscardsReadinessFromAnInFlightStart() {
        RuntimeGeneration state = new RuntimeGeneration();
        long first = state.requestStart();
        state.requestStop();
        assertFalse(state.isCurrent(first));
        assertFalse(state.isCurrent(0));
    }

    @Test public void restartDoesNotReviveTheOldInitialization() {
        RuntimeGeneration state = new RuntimeGeneration();
        long first = state.requestStart();
        state.requestStop();
        long second = state.requestStart();
        assertTrue(second > first);
        assertFalse(state.isCurrent(first));
        assertTrue(state.isCurrent(second));
    }
}
