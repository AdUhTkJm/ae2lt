package com.moakiee.thunderbolt.api.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class PlanningEngineSessionContextTest {
    @Test
    void contextIsPartOfTheOnlyAttemptContract() {
        var checkpoints = new AtomicInteger();
        PlanningEngineSession session = (amount, simulate, context) -> {
            context.checkpoint();
            return PlanningAttempt.DECLINE;
        };
        var context = new PlanningAttemptContext() {
            @Override
            public long deadlineNanos() {
                return Long.MAX_VALUE;
            }

            @Override
            public void checkpoint() {
                checkpoints.incrementAndGet();
            }

            @Override
            public void report(PlanningDiagnosticSnapshot snapshot) {
            }
        };

        var result = session.attempt(64, false, context);

        assertSame(PlanningAttempt.DECLINE, result);
        assertEquals(1, checkpoints.get());
    }

    @Test
    void sessionHasExplicitCleanupContract() {
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        PlanningEngineSession session = new PlanningEngineSession() {
            @Override
            public PlanningAttempt attempt(
                    long amount, boolean simulate, PlanningAttemptContext context) {
                return PlanningAttempt.DECLINE;
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };

        session.close();

        assertTrue(closed.get());
    }
}
