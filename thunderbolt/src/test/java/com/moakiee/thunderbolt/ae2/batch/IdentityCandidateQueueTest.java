package com.moakiee.thunderbolt.core.crafting.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;

import org.junit.jupiter.api.Test;

class IdentityCandidateQueueTest {
    @Test
    void ninetyNineFailedProvidersAreTriedOnceThenPhysicallyRemoved() {
        var providers = new ArrayList<FakeProvider>();
        for (int i = 0; i < 99; i++) providers.add(new FakeProvider(false));
        var successful = new FakeProvider(true);
        providers.add(successful);
        var queue = new IdentityCandidateQueue<>(providers);

        dispatchOnce(queue);

        assertEquals(1, queue.activeCount());
        assertEquals(99, queue.blockedCount());
        for (int i = 0; i < 99; i++) {
            assertEquals(1, providers.get(i).attempts);
            assertTrue(queue.isBlocked(providers.get(i)));
        }

        for (int i = 0; i < 1_000; i++) {
            var iterator = queue.iterator();
            assertTrue(iterator.hasNext());
            assertSame(successful, iterator.next());
            assertFalse(iterator.hasNext());
            assertTrue(successful.dispatch());
            queue.markSuccess(successful);
        }

        assertEquals(1_001, successful.attempts);
        for (int i = 0; i < 99; i++) {
            assertEquals(1, providers.get(i).attempts);
        }
    }

    private static void dispatchOnce(IdentityCandidateQueue<FakeProvider> queue) {
        for (var provider : queue) {
            if (provider.dispatch()) {
                queue.markSuccess(provider);
                return;
            }
            queue.block(provider);
        }
    }

    private static final class FakeProvider {
        private final boolean accepts;
        private int attempts;

        private FakeProvider(boolean accepts) {
            this.accepts = accepts;
        }

        private boolean dispatch() {
            attempts++;
            return accepts;
        }
    }
}
