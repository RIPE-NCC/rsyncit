package net.ripe.rpki.rsyncit.config;

import lombok.Value;

import java.time.Duration;

@Value
public class Config {
    String rrdpUrl;
    String rsyncPath;
    String cron;
    Duration totalRequestTimeout;
}
