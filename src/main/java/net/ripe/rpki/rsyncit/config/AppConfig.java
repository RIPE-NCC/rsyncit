package net.ripe.rpki.rsyncit.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

@Component
@Getter
public class AppConfig implements InfoContributor {

    private final String rrdpUrl;
    private final String rrdpReplaceHostWith;
    private final String rsyncPath;
    private final String cron;
    private final Duration requestTimeout;
    private final ApplicationInfo info;
    private final long targetDirectoryRetentionPeriodMs;
    private final int targetDirectoryRetentionCopiesCount;

    public AppConfig(@Value("${rrdpUrl}") String rrdpUrl,
                     @Value("${rrdpReplaceHost:}") String rrdpReplaceHostWith,
                     @Value("${rsyncPath}") String rsyncPath,
                     // Run every 10 minutes
                     @Value("${cron:0 0/10 * * * ?}") String cron,
                     // 3 minutes by default
                     @Value("${requestTimeout:PT180S}") Duration requestTimeout,
                     // delete rsync directories older than 1 hour
                     @Value("${targetDirectoryRetentionPeriodMs:3600000}") long targetDirectoryRetentionPeriodMs,
                     // do not keep more than 8 copies of rsync directories at once
                     @Value("${targetDirectoryRetentionCopiesCount:8}") int targetDirectoryRetentionCopiesCount,
                     ApplicationInfo info,
                     MeterRegistry registry) {
        this.rrdpUrl = rrdpUrl;
        this.rrdpReplaceHostWith = rrdpReplaceHostWith;
        this.rsyncPath = rsyncPath;
        this.cron = cron;
        this.requestTimeout = requestTimeout;
        this.info = info;
        this.targetDirectoryRetentionPeriodMs = targetDirectoryRetentionPeriodMs;
        this.targetDirectoryRetentionCopiesCount = targetDirectoryRetentionCopiesCount;

        Gauge.builder("rsyncit.configuration", () -> 1.0)
                .baseUnit("info")
                .tag("rrdp_url", rrdpUrl)
                .tag("rrdp_override_host", rrdpReplaceHostWith)
                .tag("request_timeout_seconds", String.valueOf(requestTimeout.toSeconds()))
                .tag("retention_period_minutes", String.valueOf(Duration.ofMillis(targetDirectoryRetentionPeriodMs).toMinutes()))
                .tag("retention_copies", String.valueOf(targetDirectoryRetentionCopiesCount))
                .tag("build", info.gitCommitId())
                .strongReference(true)
                .register(registry);
    }

    public Config getConfig() {
        return new Config(rrdpUrl, substitutor(rrdpReplaceHostWith), rsyncPath, cron, requestTimeout,
            targetDirectoryRetentionPeriodMs, targetDirectoryRetentionCopiesCount);
    }

    static Function<String, String> substitutor(String rrdpReplaceHostWith) {
        if (rrdpReplaceHostWith == null || rrdpReplaceHostWith.isBlank()) {
            return Function.identity();
        }
        final String[] split = rrdpReplaceHostWith.split("/");
        if (split.length != 2) {
            throw new IllegalArgumentException("rrdpReplaceHost must be of the form 'host1.net/host2.com'");
        }
        return rrdpUrl -> rrdpUrl.replaceAll(split[0], split[1]);
    }

    @Bean
    public static ApplicationInfo appInfo(GitProperties gitProperties) {
        return new ApplicationInfo(gitProperties.getShortCommitId());
    }
    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("config", Map.of(
            "cron", cron,
            "rrdp_url", rrdpUrl,
            "rrdp_replace_host", rrdpReplaceHostWith,
            "rsync_path", rsyncPath,
            "request_timeout_seconds", String.valueOf(requestTimeout.toSeconds()),
            "retention_period_minutes", String.valueOf(Duration.ofMillis(targetDirectoryRetentionPeriodMs).toMinutes()),
            "retention_copies", String.valueOf(targetDirectoryRetentionCopiesCount),
            "build", info.gitCommitId()
        ));
    }
}
