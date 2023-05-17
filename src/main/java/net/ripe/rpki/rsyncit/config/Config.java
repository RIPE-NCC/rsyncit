package net.ripe.rpki.rsyncit.config;

import java.time.Duration;

public record Config(
    String rrdpUrl,
    String rsyncPath,
    String cron,
    Duration requestTimeout,
    long targetDirectoryRetentionPeriodMs,
    int targetDirectoryRetentionCopiesCount
) {
}
