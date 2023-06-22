package net.ripe.rpki.rsyncit.rrdp;

import com.google.common.util.concurrent.AtomicDouble;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.atomic.AtomicInteger;

public final class RRDPFetcherMetrics {
    private final AtomicInteger rrdpSerial = new AtomicInteger();
    private final AtomicDouble snapshotDownloadMs = new AtomicDouble();
    private final Counter successfulUpdates;
    private final Counter failedUpdates;
    private final Counter timeoutUpdates;

    public RRDPFetcherMetrics(MeterRegistry meterRegistry) {
        successfulUpdates = buildCounter("success", meterRegistry);
        failedUpdates = buildCounter("failed", meterRegistry);
        timeoutUpdates = buildCounter("timeout", meterRegistry);

        Gauge.builder("rsyncit.fetcher.rrdp.serial", rrdpSerial::get)
            .description("Serial of the RRDP notification.xml at the given URL")
            .register(meterRegistry);

        Gauge.builder("rsyncit.fetcher.rrdp.snapshot.download.time", snapshotDownloadMs::get)
            .description("Time to download snapshot")
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

    private static Counter buildCounter(String statusTag, MeterRegistry registry) {
        return Counter.builder("rsyncit.fetcher.updated")
            .description("Number of fetches")
            .tag("status", statusTag)
            .register(registry);
    }

    public void snapshotDownloadTime(long time) {
        this.snapshotDownloadMs.set(time);
    }
}
