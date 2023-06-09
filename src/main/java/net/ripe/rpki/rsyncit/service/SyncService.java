package net.ripe.rpki.rsyncit.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.ripe.rpki.rsyncit.config.AppConfig;
import net.ripe.rpki.rsyncit.rrdp.RRDPFetcherMetrics;
import net.ripe.rpki.rsyncit.rrdp.RrdpFetcher;
import net.ripe.rpki.rsyncit.rrdp.State;
import net.ripe.rpki.rsyncit.rsync.RsyncWriter;
import net.ripe.rpki.rsyncit.util.Time;
import net.ripe.rpki.rsyncit.util.http.WebClientBuilderFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
@Getter
public class SyncService {

    private final WebClientBuilderFactory webClientFactory;
    private final AppConfig appConfig;
    private final State state;
    private final MeterRegistry meterRegistry;
    private final RRDPFetcherMetrics metrics;

    @Autowired
    public SyncService(WebClientBuilderFactory webClientFactory,
                       AppConfig appConfig,
                       MeterRegistry meterRegistry) {
        this.webClientFactory = webClientFactory;
        this.appConfig = appConfig;
        this.meterRegistry = meterRegistry;
        this.metrics = new RRDPFetcherMetrics(meterRegistry);
        this.state = new State();
    }

    public void sync() {
        var config = appConfig.getConfig();
        var rrdpFetcher = new RrdpFetcher(config, webClientFactory.builder().build(), state);

        var t = Time.timed(rrdpFetcher::fetchObjects);
        final RrdpFetcher.FetchResult fetchResult = t.getResult();
        if (fetchResult instanceof RrdpFetcher.NoUpdates noUpdates) {
            metrics.success(noUpdates.serial());
            log.info("Session id {} and serial {} have not changed since the last check, nothing to update",
                noUpdates.sessionId(), noUpdates.serial());
        } else if (fetchResult instanceof RrdpFetcher.SuccessfulFetch success) {
            metrics.success(success.serial());
            log.info("Fetched {} objects in {}ms", success.objects().size(), t.getTime());
            state.setRrdpState(new State.RrdpState(success.sessionId(), success.serial()));
            log.info("Updated RRDP state to session_id {} and serial {}", success.sessionId(), success.serial());

            var rsyncWriter = new RsyncWriter(config);
            var r = Time.timed(() -> rsyncWriter.writeObjects(success.objects()));
            log.info("Wrote objects to {} in {}ms", r.getResult(), r.getTime());

            state.getRrdpState().markInSync();

            // Remove objects that were in old snapshots and didn't appear for a while
            state.removeOldObject(Instant.now().minus(1, ChronoUnit.HOURS));
        } else if (fetchResult instanceof RrdpFetcher.FailedFetch failed) {
            metrics.failure();
            log.error("Failed to fetch RRDP:", failed.exception());
            state.setRrdpState(new State.RrdpState(failed.exception().getMessage()));
        } else if (fetchResult instanceof RrdpFetcher.Timeout) {
            metrics.timeout();
        } else {
            // Should never happen?
            log.error("Error, unknown fetch status");
        }
    }

}
