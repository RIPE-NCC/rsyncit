package net.ripe.rpki.rsyncit.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
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

    public AppConfig(@Value("${rrdpUrl}") String rrdpUrl,
                     @Value("${rsyncPath}") String rsyncPath,
                     @Value("${cron}") String cron,
                     @Value("${requestTimeout}") String requestTimeout,
                     ApplicationInfo info) {
        this.rrdpUrl = rrdpUrl;
        this.rsyncPath = rsyncPath;
        this.cron = cron;
        this.requestTimeout = requestTimeout;
        this.info = info;
    }

    public Config getConfig() {
        return new Config(rrdpUrl, rsyncPath, cron, Duration.parse(requestTimeout));
    }

    @Bean
    public static ApplicationInfo appInfo(GitProperties gitProperties) {
        return new ApplicationInfo(gitProperties.getShortCommitId());
    }

}
