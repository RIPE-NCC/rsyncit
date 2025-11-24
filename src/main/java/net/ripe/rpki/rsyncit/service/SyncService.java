package net.ripe.rpki.rsyncit.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.ripe.rpki.rsyncit.config.AppConfig;
import net.ripe.rpki.rsyncit.config.Config;
import net.ripe.rpki.rsyncit.rrdp.RRDPFetcherMetrics;
import net.ripe.rpki.rsyncit.rrdp.RrdpFetcher;
import net.ripe.rpki.rsyncit.rrdp.State;
import net.ripe.rpki.rsyncit.rsync.RsyncWriter;
import net.ripe.rpki.rsyncit.util.Time;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
@Getter
public class SyncService {

    private final WebClient webClient;
    private final AppConfig appConfig;
    private final State state;
    private final RRDPFetcherMetrics metrics;
    private volatile boolean isRunning = false;

    @Autowired
    public SyncService(WebClient webClient,
                       AppConfig appConfig,
                       MeterRegistry meterRegistry) {
        this.appConfig = appConfig;
        this.webClient = webClient;
        this.metrics = new RRDPFetcherMetrics(meterRegistry);
        this.state = new State();
    }

    public void sync() {
        // Do not mutate isRunning here, do it inside the try block
        boolean shouldRun = !isRunning;
        if (isRunning) {
            log.info("Sync is already running, skipping this run. Most likely it means that the system is abnormally slow.");
            metrics.tooSlow();
            return;
        }
        if (shouldRun) {
            try {
                isRunning = true;
                doSync();
            } finally {
                isRunning = false;
            }
        }
    }

    private void doSync() {
        var config = appConfig.getConfig();
        var rrdpFetcher = new RrdpFetcher(config, webClient, state, metrics);

        var t = Time.timed(rrdpFetcher::fetchObjects);
        final RrdpFetcher.FetchResult fetchResult = t.getResult();

        switch (fetchResult) {
            case RrdpFetcher.NoUpdates noUpdates -> noUpdates(noUpdates);
            case RrdpFetcher.SuccessfulFetch success -> onSuccess(success, t, config);
            case RrdpFetcher.FailedFetch failed -> onFailure(failed);
            case RrdpFetcher.Timeout timeout -> metrics.timeout();
            case null, default ->
                throw new UnsupportedOperationException("Unknown fetch result: " + fetchResult);
        }
    }

    private void onSuccess(RrdpFetcher.SuccessfulFetch success, Time.Timed<RrdpFetcher.FetchResult> t, Config config) {
        if (config.minimalObjectCountCheckEnabled()) {
            if (success.objects().size() < config.minimalObjectCount()) {
                log.error("Will not write objects to the rsync repository: the number of objects {} is smaller than the minimal threshold {}.",
                        success.objects().size(), config.minimalObjectCount());
                metrics.rejectAsTooSmall();
                return;
            }
        }
        metrics.success(success.serial());
        log.info("Fetched {} objects in {}ms", success.objects().size(), t.getTime());
        state.setRrdpState(new State.RrdpState(success.sessionId(), success.serial()));
        log.info("Updated RRDP state to session_id {} and serial {}", success.sessionId(), success.serial());

        var rsyncWriter = new RsyncWriter(config);
        var r = Time.timed(() -> {
            try {
                return rsyncWriter.writeObjects(success.objects(), Instant.now());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        log.info("Wrote objects to {} in {}ms", r.getResult(), r.getTime());

        state.getRrdpState().markInSync();

        // Remove objects that were in old snapshots and didn't appear for a while
        state.removeOldObject(Instant.now().minus(1, ChronoUnit.HOURS));
    }

    private void noUpdates(RrdpFetcher.NoUpdates noUpdates) {
        metrics.success(noUpdates.serial());
        log.info("Session id {} and serial {} have not changed since the last check, nothing to update",
                noUpdates.sessionId(), noUpdates.serial());
    }

    private void onFailure(RrdpFetcher.FailedFetch failed) {
        metrics.failure();
        log.error("Failed to fetch RRDP:", failed.exception());
        state.setRrdpState(new State.RrdpState(failed.exception().getMessage()));
    }
}
