package net.ripe.rpki.rsyncit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.http.ContentType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.resolver.ResolvedAddressTypes;
import lombok.extern.slf4j.Slf4j;
import net.ripe.rpki.rsyncit.config.AppConfig;
import net.ripe.rpki.rsyncit.rrdp.RrdpFetchJob;
import net.ripe.rpki.rsyncit.rrdp.State;
import net.ripe.rpki.rsyncit.service.HealthController;
import net.ripe.rpki.rsyncit.service.SyncService;
import org.quartz.Scheduler;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class RsyncitApplication {

    public static void main(String[] args) {
        try {
            log.info("Starting rsyncit application...");

            // Create metrics registry
            PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

            // Create configuration
            AppConfig appConfig = new AppConfig(meterRegistry);
            log.info("Configuration loaded: rrdpUrl={}, rsyncPath={}",
                appConfig.getRrdpUrl(), appConfig.getRsyncPath());

            // Create HTTP client for RRDP fetching
            HttpClient httpClient = createHttpClient(appConfig);

            // Create services
            SyncService syncService = new SyncService(httpClient, appConfig, meterRegistry);
            HealthController healthController = new HealthController(syncService);

            // Create and start HTTP server
            Javalin app = createHttpServer(appConfig, healthController, meterRegistry);

            // Create and start scheduler
            Scheduler scheduler = RrdpFetchJob.createAndStartScheduler(appConfig, syncService);

            // Run initial sync
            log.info("Running initial sync...");
            try {
                syncService.sync();
            } catch (Exception e) {
                log.error("Initial sync failed", e);
            }

            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down...");
                try {
                    scheduler.shutdown(true);
                    app.stop();
                } catch (Exception e) {
                    log.error("Error during shutdown", e);
                }
            }));

            log.info("rsyncit application started successfully on port {}", appConfig.getServerPort());

        } catch (Exception e) {
            log.error("Failed to start application", e);
            System.exit(1);
        }
    }

    private static HttpClient createHttpClient(AppConfig appConfig) {
        final var userAgent = "rsyncit %s".formatted(appConfig.getInfo().gitCommitId());

        return HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .responseTimeout(Duration.ofMillis(5000))
            .headers(h -> h.add("User-Agent", userAgent))
            .doOnConnected(conn ->
                conn.addHandlerLast(new ReadTimeoutHandler(5000, TimeUnit.MILLISECONDS))
                    .addHandlerLast(new WriteTimeoutHandler(5000, TimeUnit.MILLISECONDS))
            )
            .resolver(spec ->
                spec.resolvedAddressTypes(ResolvedAddressTypes.IPV6_PREFERRED)
                    .completeOncePreferredResolved(false));
    }

    private static Javalin createHttpServer(AppConfig appConfig,
                                            HealthController healthController,
                                            PrometheusMeterRegistry meterRegistry) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
        }).start(appConfig.getServerPort());

        // Status endpoint
        app.get("/status", ctx -> {
            State.RrdpState status = healthController.getStatus();
            if (status == null) {
                ctx.status(404);
            } else {
                ctx.contentType(ContentType.APPLICATION_JSON);
                ctx.result(objectMapper.writeValueAsString(status));
            }
        });

        // Health endpoint
        app.get("/actuator/health", ctx -> {
            ctx.contentType(ContentType.APPLICATION_JSON);
            ctx.result("{\"status\":\"UP\"}");
        });

        // Info endpoint
        app.get("/actuator/info", ctx -> {
            ctx.contentType(ContentType.APPLICATION_JSON);
            Map<String, Object> info = Map.of(
                "config", appConfig.getConfigMap(),
                "git", Map.of("commit", Map.of("id", appConfig.getInfo().gitCommitId()))
            );
            ctx.result(objectMapper.writeValueAsString(info));
        });

        // Prometheus metrics endpoint
        app.get("/actuator/prometheus", ctx -> {
            ctx.contentType("text/plain; version=0.0.4; charset=utf-8");
            ctx.result(meterRegistry.scrape());
        });

        log.info("HTTP server started on port {}", appConfig.getServerPort());
        return app;
    }
}
