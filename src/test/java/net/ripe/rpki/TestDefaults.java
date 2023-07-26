package net.ripe.rpki;

import net.ripe.rpki.rsyncit.config.Config;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

public class TestDefaults {
    public static Config defaultConfig() {
        return defaultConfig("/tmp/rsync");
    }

    public static Config defaultConfig(String rsyncPath) {
        return new Config("https://rrdp.ripe.net/notification.xml",
            rsyncPath,
            "0 0/10 * * * ?",
            Duration.of(1, ChronoUnit.MINUTES),
            3600_000, 10);
    }

    public static WebClient defaultWebClient() {
        return WebClient.builder().build();
    }
}
