package net.ripe.rpki.rsyncit.rrdp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Value;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@Value
public class State {
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

    @Data
    @AllArgsConstructor
    static class Times {
        Instant createdAt;
        Instant lastMentioned;
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
}
