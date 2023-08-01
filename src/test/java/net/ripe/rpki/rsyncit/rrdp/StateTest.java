package net.ripe.rpki.rsyncit.rrdp;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class StateTest {

    @Test
    public void testCachedTimestamps() {
        var state = new State();
        var createdAt1 = Instant.ofEpochMilli(1000_000_000);
        var createdAt2 = Instant.ofEpochMilli(1000_100_000);
        var now = Instant.now();
        assertEquals(createdAt1, state.cacheTimestamps("hash1", now, () -> createdAt1));
        assertEquals(createdAt1, state.cacheTimestamps("hash1", now, () -> createdAt1));
        assertEquals(createdAt2, state.cacheTimestamps("hash2", now, () -> createdAt2));
        assertEquals(createdAt2, state.cacheTimestamps("hash2", now, () -> createdAt2));
    }

    @Test
    public void testRemoveOldEntries() {
        var state = new State();

        for (int i = 0; i < 10 ; i++) {
            var createdAt = Instant.ofEpochMilli(1000_000_000 + 10_000_000 * i);
            var now = Instant.ofEpochMilli(1000_000_000 + 10_000 * i);
            state.cacheTimestamps("hash" + i, now, () -> createdAt);
        }
        assertEquals(10, state.getTimes().size());

        state.removeOldObject(Instant.ofEpochMilli(1000_000_000 + 10_000 * 5));
        assertEquals(5, state.getTimes().size());

        for (int i = 5; i < 10; i++) {
            var createdAt = Instant.ofEpochMilli(1000_000_000 + 10_000_000 * i);
            var mentioned = Instant.ofEpochMilli(1000_000_000 + 10_000 * i);
            final State.Times times = state.getTimes().get("hash" + i);
            assertEquals(createdAt, times.getCreatedAt());
            assertEquals(mentioned, times.getLastMentioned());
        }
    }

}