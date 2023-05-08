package net.ripe.rpki.rsyncit.rrdp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@Getter
public class State {

    @Setter
    RrdpState rrdpState;
    Map<String, Times> times;

    public State() {
        this.times = new HashMap<>();
    }

    // Remove entries that were not mentioned in RRDP repository for a while
    public synchronized void removeOldObject(Instant cutOffTime) {
        var hashesToDelete = new ArrayList<>();
        times.forEach((hash, t) -> {
            if (t.getLastMentioned().isBefore(cutOffTime)) {
                hashesToDelete.add(hash);
            }
        });
        hashesToDelete.forEach(times::remove);
    }

    public synchronized Instant getOrUpdateCreatedAt(String hash, Instant now) {
        var ts = times.get(hash);
        if (ts == null) {
            times.put(hash, new Times(now, now));
            return now;
        } else {
            ts.setLastMentioned(now);
            return ts.getCreatedAt();
        }
    }

    @Data
    @AllArgsConstructor
    static class Times {
        Instant createdAt;
        Instant lastMentioned;
    }

    @Getter
    public static class RrdpState {
        private final String sessionId;
        private final Integer serial;
        private final Instant createdAt;
        private boolean inSync;

        public RrdpState(String sessionId, Integer serial) {
            this.sessionId = sessionId;
            this.serial = serial;
            createdAt = Instant.now();
        }

        public void synced() {
            inSync = true;
        }
    }
}
