package net.ripe.rpki.rsyncit.rrdp;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.atomic.AtomicInteger;

public final class RRDPFetcherMetrics {
    private final AtomicInteger rrdpSerial = new AtomicInteger();
    private final Counter successfulUpdates;
    private final Counter failedUpdates;
    private final Counter timeoutUpdates;
    private final Counter objectFailures;

    public final Timer objectConstructionTimer;

    public RRDPFetcherMetrics(MeterRegistry meterRegistry) {
        successfulUpdates = buildCounter("success", meterRegistry);
        failedUpdates = buildCounter("failed", meterRegistry);
        timeoutUpdates = buildCounter("timeout", meterRegistry);
        objectFailures = Counter.builder("rsyncit.fetcher.objects")
            .description("Metrics on objects")
            .tag("status", "failure")
            .register(meterRegistry);

        Gauge.builder("rsyncit.fetcher.rrdp.serial", rrdpSerial::get)
            .description("Serial of the RRDP notification.xml at the given URL")
            .register(meterRegistry);

        objectConstructionTimer = Timer.builder("rsyncit.fetcher.parsing")
                .description("Time spent parsing objects for the last run")
                .register(meterRegistry);
    }

    public void success(int serial) {
        this.successfulUpdates.increment();
        this.rrdpSerial.set(serial);
    }

    public void failure() {
        this.failedUpdates.increment();
    }

    public void timeout() {
        this.timeoutUpdates.increment();
    }

    public void badObject() {
        this.objectFailures.increment();
    }

    private static Counter buildCounter(String statusTag, MeterRegistry registry) {
        return Counter.builder("rsyncit.fetcher.updated")
            .description("Number of fetches")
            .tag("status", statusTag)
            .register(registry);
    }

}
