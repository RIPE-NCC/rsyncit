package net.ripe.rpki.rsyncit.rrdp;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Getter
public class State {

    @Setter
    RrdpState rrdpState;
    ConcurrentHashMap<String, Times> times;

    public State() {
        this.times = new ConcurrentHashMap<>();
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

    public Instant cacheTimestamps(String hash, Instant now, Supplier<Instant> createdAt) {
        var ts = times.computeIfAbsent(hash, h -> new Times(createdAt.get(), now));
        ts.setLastMentioned(now);
        return ts.getCreatedAt();
    }

    @Data
    @AllArgsConstructor
    static class Times {
        Instant createdAt;
        Instant lastMentioned;
    }

    @Getter
    public static class RrdpState {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private final String sessionId;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private final Integer serial;
        private final Instant createdAt;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String failure;
        private boolean inSync;

        public RrdpState(String sessionId, Integer serial) {
            this.sessionId = sessionId;
            this.serial = serial;
            createdAt = Instant.now();
        }

        public RrdpState(String failure) {
            this.failure = failure;
            this.sessionId = null;
            this.serial = null;
            createdAt = Instant.now();
        }

        public void markInSync() {
            inSync = true;
        }
    }
}
