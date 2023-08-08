package net.ripe.rpki.rsyncit.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Function;

@Component
@Getter
public class AppConfig {

    private final String rrdpUrl;
    private final String rrdpReplaceHostWith;
    private final String rsyncPath;
    private final String cron;
    private final String requestTimeout;
    private final ApplicationInfo info;
    private final long targetDirectoryRetentionPeriodMs;
    private final int targetDirectoryRetentionCopiesCount;

    public AppConfig(@Value("${rrdpUrl}") String rrdpUrl,
                     @Value("${rrdpReplaceHost:}") String rrdpReplaceHostWith,
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
        this.rrdpReplaceHostWith = rrdpReplaceHostWith;
        this.rsyncPath = rsyncPath;
        this.cron = cron;
        this.requestTimeout = requestTimeout;
        this.info = info;
        this.targetDirectoryRetentionPeriodMs = targetDirectoryRetentionPeriodMs;
        this.targetDirectoryRetentionCopiesCount = targetDirectoryRetentionCopiesCount;
    }

    public Config getConfig() {
        return new Config(rrdpUrl, substitutor(rrdpReplaceHostWith), rsyncPath, cron, Duration.parse(requestTimeout),
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

}
