package net.ripe.rpki;

import net.ripe.rpki.rsyncit.config.Config;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Paths;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.function.Function;

public class TestDefaults {
    public static Config defaultConfig() {
        return new Config("https://rrdp.ripe.net/notification.xml",
            Function.identity(),
                Paths.get("/tmp/rsync"),
            "0 0/10 * * * ?",
            Duration.of(1, ChronoUnit.MINUTES),
            3600_000, 10, 0, false);
    }

    public static WebClient defaultWebClient() {
        return WebClient.builder().build();
    }
}
