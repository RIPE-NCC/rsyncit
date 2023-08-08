package net.ripe.rpki.rsyncit.config;

import lombok.With;

import java.time.Duration;
import java.util.function.Function;

@With
public record Config(
    String rrdpUrl,
    Function<String, String> substituteHost,
    String rsyncPath,
    String cron,
    Duration requestTimeout,
    long targetDirectoryRetentionPeriodMs,
    int targetDirectoryRetentionCopiesCount
) {
}
