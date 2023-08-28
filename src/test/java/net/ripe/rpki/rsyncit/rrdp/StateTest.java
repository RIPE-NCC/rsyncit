package net.ripe.rpki.rsyncit.rrdp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StateTest {
    private State subject;

    @BeforeEach
    public void setUp() {
        subject = new State();
    }

    @Test
    public void testBaseCase() {
        assertThat(subject.getTimes()).isEmpty();
    }

    @Test
    public void testCachedTimestamps() {
        var now = Instant.now();

        var subject = new State();
        var createdAt1 = Instant.ofEpochMilli(1000_000_000);
        var createdAt2 = Instant.ofEpochMilli(1000_100_000);

        assertThat(subject.cacheTimestamps("hash1", now, () -> createdAt1)).isEqualTo(createdAt1);
        assertThat(subject.cacheTimestamps("hash1", now, () -> createdAt1)).isEqualTo(createdAt1);

        assertThat(subject.cacheTimestamps("hash2", now, () -> createdAt2)).isEqualTo(createdAt2);
        assertThat(subject.cacheTimestamps("hash2", now, () -> createdAt2)).isEqualTo(createdAt2);

        assertThat(subject.getTimes()).hasSize(2);
    }

    @Test
    public void testRemoveOldEntries() {
        for (int i = 0; i < 10 ; i++) {
            var createdAt = Instant.ofEpochMilli(1000_000_000 + 10_000_000 * i);
            var now = Instant.ofEpochMilli(1000_000_000 + 10_000 * i);
            subject.cacheTimestamps("hash" + i, now, () -> createdAt);
        }

        assertThat(subject.getTimes()).hasSize(10);

        subject.removeOldObject(Instant.ofEpochMilli(1000_000_000 + 10_000 * 5));
        assertThat(subject.getTimes()).hasSize(5);

        for (int i = 5; i < 10; i++) {
            var createdAt = Instant.ofEpochMilli(1000_000_000 + 10_000_000 * i);
            var mentioned = Instant.ofEpochMilli(1000_000_000 + 10_000 * i);
            final State.Times times = subject.getTimes().get("hash" + i);
            assertThat(times.getCreatedAt()).isEqualTo(createdAt);
            assertThat(times.getLastMentioned()).isEqualTo(mentioned);
        }
    }

}