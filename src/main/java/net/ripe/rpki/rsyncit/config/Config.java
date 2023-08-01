package net.ripe.rpki.rsyncit.config;

import lombok.With;

import java.time.Duration;

@With
public record Config(
    String rrdpUrl,
    String rsyncPath,
    String cron,
    Duration requestTimeout,
    long targetDirectoryRetentionPeriodMs,
    int targetDirectoryRetentionCopiesCount
) {
}
