package net.ripe.rpki.rsyncit.service;

import lombok.extern.slf4j.Slf4j;
import net.ripe.rpki.rsyncit.config.AppConfig;
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
public class SyncService {

    private final WebClientBuilderFactory webClientFactory;
    private final AppConfig appConfig;
    private final State state;

    @Autowired
    public SyncService(WebClientBuilderFactory webClientFactory, AppConfig appConfig) {
        this.webClientFactory = webClientFactory;
        this.appConfig = appConfig;
        this.state = new State();
    }

    public void sync() {
        var config = appConfig.getConfig();
        var rrdpFetcher = new RrdpFetcher(config, webClientFactory.builder().build(), state);
        var t = Time.timed(rrdpFetcher::fetchObjects);
        var objects = t.getResult();
        log.info("Fetched {} objects in {}ms", objects.size(), t.getTime());

        var rsyncWriter = new RsyncWriter(config);
        var t1 = Time.timed(() -> rsyncWriter.writeObjects(objects));
        log.info("Wrote objects to {} in {}ms", t1.getResult(), t1.getTime());

        // Remove objects that were in old snapshots and didn't appear for a while
        state.removeOldObject(Instant.now().minus(1, ChronoUnit.HOURS));
    }

}
