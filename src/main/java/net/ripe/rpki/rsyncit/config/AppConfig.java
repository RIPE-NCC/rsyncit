package net.ripe.rpki.rsyncit.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@Getter
public class AppConfig {

    private final String rrdpUrl;
    private final String rsyncPath;
    private final String cron;
    private final String requestTimeout;
    private final ApplicationInfo info;
    private final long targetDirectoryRetentionPeriodMs;
    private final int targetDirectoryRetentionCopiesCount;

    public AppConfig(@Value("${rrdpUrl}") String rrdpUrl,
                     @Value("${rsyncPath}") String rsyncPath,
                     // Run every 10 minutes
                     @Value("${cron:0 0/10 * * * ?}") String cron,
                     // 3 minutes by default
                     @Value("${requestTimeout:PT180S}") String requestTimeout,
                     // delete rsync directories older than 1 hour
                     @Value("${targetDirectoryRetentionPeriodMs:3600000}") long targetDirectoryRetentionPeriodMs,
                     // do not keep more than 8 copies of rsync directories at once
                     @Value("${targetDirectoryRetentionCopiesCount:8}") int targetDirectoryRetentionCopiesCount,
                     ApplicationInfo info) {
        this.rrdpUrl = rrdpUrl;
        this.rsyncPath = rsyncPath;
        this.cron = cron;
        this.requestTimeout = requestTimeout;
        this.info = info;
        this.targetDirectoryRetentionPeriodMs = targetDirectoryRetentionPeriodMs;
        this.targetDirectoryRetentionCopiesCount = targetDirectoryRetentionCopiesCount;
    }

    public Config getConfig() {
        return new Config(rrdpUrl, rsyncPath, cron, Duration.parse(requestTimeout),
            targetDirectoryRetentionPeriodMs, targetDirectoryRetentionCopiesCount);
    }

    @Bean
    public static ApplicationInfo appInfo(GitProperties gitProperties) {
        return new ApplicationInfo(gitProperties.getShortCommitId());
    }

}
