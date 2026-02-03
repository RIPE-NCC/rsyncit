package net.ripe.rpki.rsyncit.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Getter;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

@Getter
public class AppConfig {

    private final String rrdpUrl;
    private final String rrdpReplaceHostWith;
    private final Path rsyncPath;
    private final String cron;
    private final Duration requestTimeout;
    private final ApplicationInfo info;
    private final long targetDirectoryRetentionPeriodMs;
    private final int targetDirectoryRetentionCopiesCount;
    private final boolean minimalObjectCountCheckEnabled;
    private final int minimalObjectCount;
    private final int serverPort;

    public AppConfig(MeterRegistry registry) {
        this.rrdpUrl = getEnvRequired("RRDP_URL");
        this.rrdpReplaceHostWith = getEnv("RRDP_REPLACE_HOST", "");
        this.rsyncPath = Path.of(getEnvRequired("RSYNC_PATH"));
        this.cron = getEnv("CRON", "0 0/10 * * * ?");
        this.requestTimeout = Duration.parse(getEnv("REQUEST_TIMEOUT", "PT180S"));
        this.targetDirectoryRetentionPeriodMs = Long.parseLong(getEnv("TARGET_DIRECTORY_RETENTION_PERIOD_MS", "3600000"));
        this.targetDirectoryRetentionCopiesCount = Integer.parseInt(getEnv("TARGET_DIRECTORY_RETENTION_COPIES_COUNT", "8"));
        this.minimalObjectCountCheckEnabled = Boolean.parseBoolean(getEnv("MINIMAL_OBJECT_COUNT_CHECK_ENABLED", "false"));
        this.minimalObjectCount = Integer.parseInt(getEnv("MINIMAL_OBJECT_COUNT", "0"));
        this.serverPort = Integer.parseInt(getEnv("SERVER_PORT", "8080"));

        // Read git commit id from git.properties if available
        String gitCommitId = loadGitCommitId();
        this.info = new ApplicationInfo(gitCommitId);

        var builder = Gauge.builder("rsyncit.configuration", () -> 1.0)
                .baseUnit("info")
                .tag("rrdp_url", rrdpUrl)
                .tag("rrdp_override_host", rrdpReplaceHostWith)
                .tag("request_timeout_seconds", String.valueOf(requestTimeout.toSeconds()))
                .tag("retention_period_minutes", String.valueOf(Duration.ofMillis(targetDirectoryRetentionPeriodMs).toMinutes()))
                .tag("retention_copies", String.valueOf(targetDirectoryRetentionCopiesCount))
                .tag("build", info.gitCommitId());

        if (minimalObjectCountCheckEnabled) {
            if (minimalObjectCount <= 0) {
                throw new IllegalArgumentException("minimalObjectCount must be > 0");
            }
            builder = builder.tag("minimal_object_count", String.valueOf(minimalObjectCount));
        }

        builder.strongReference(true).register(registry);
    }

    public Config getConfig() {
        return new Config(rrdpUrl, substitutor(rrdpReplaceHostWith), rsyncPath, cron, requestTimeout,
                targetDirectoryRetentionPeriodMs, targetDirectoryRetentionCopiesCount,
                minimalObjectCount, minimalObjectCountCheckEnabled);
    }

    public Map<String, Object> getConfigMap() {
        return Map.of(
                "cron", cron,
                "rrdp_url", rrdpUrl,
                "rrdp_replace_host", rrdpReplaceHostWith,
                "rsync_path", rsyncPath.toString(),
                "request_timeout_seconds", String.valueOf(requestTimeout.toSeconds()),
                "retention_period_minutes", String.valueOf(Duration.ofMillis(targetDirectoryRetentionPeriodMs).toMinutes()),
                "retention_copies", String.valueOf(targetDirectoryRetentionCopiesCount),
                "build", info.gitCommitId(),
                "minimal_object_count_check_enabled", String.valueOf(minimalObjectCountCheckEnabled),
                "minimal_object_count", minimalObjectCountCheckEnabled ? String.valueOf(minimalObjectCount) : "0");
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

    private static String getEnv(String name, String defaultValue) {
        return Optional.ofNullable(System.getenv(name))
                .or(() -> Optional.ofNullable(System.getProperty(name.toLowerCase().replace('_', '.'))))
                .orElse(defaultValue);
    }

    private static String getEnvRequired(String name) {
        String value = getEnv(name, null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable or system property not set: " + name);
        }
        return value;
    }

    private static String loadGitCommitId() {
        try (var is = AppConfig.class.getClassLoader().getResourceAsStream("git.properties")) {
            if (is != null) {
                var props = new java.util.Properties();
                props.load(is);
                return props.getProperty("git.commit.id.abbrev", "unknown");
            }
        } catch (Exception e) {
            // Ignore
        }
        return "unknown";
    }
}
