package net.ripe.rpki.rsyncit;

import io.micrometer.common.KeyValues;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.resolver.ResolvedAddressTypes;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientRequestObservationContext;
import org.springframework.web.reactive.function.client.ClientRequestObservationConvention;
import org.springframework.web.reactive.function.client.DefaultClientRequestObservationConvention;
import org.springframework.web.reactive.function.client.WebClient;
import net.ripe.rpki.rsyncit.config.AppConfig;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
public class RsyncitApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(RsyncitApplication.class);
        Properties properties = new Properties();
        properties.put("spring.codec.max-in-memory-size", "1GB");
        properties.put("management.endpoints.web.exposure.include", "info,prometheus,health");
        application.setDefaultProperties(properties);
        application.run(args);
    }

    @Bean
    public WebClient webclientConfiguration(WebClient.Builder baseBuilder, AppConfig appConfig) {
        final var userAgent = "rsyncit %s".formatted(appConfig.getInfo().gitCommitId());

        var httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .responseTimeout(Duration.ofMillis(5000))
                // remember: read and write timeouts are per read, not for a request.
                .doOnConnected(conn ->
                        conn.addHandlerLast(new ReadTimeoutHandler(5000, TimeUnit.MILLISECONDS))
                            .addHandlerLast(new WriteTimeoutHandler(5000, TimeUnit.MILLISECONDS))
                ).resolver(spec ->
                    spec.resolvedAddressTypes(ResolvedAddressTypes.IPV6_PREFERRED)
                        .completeOncePreferredResolved(false));


        return baseBuilder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.USER_AGENT, userAgent)
                .build();
    }

    /**
     * Return an observation customiser that only differs in that it omits the URL.
     * The hostname for the request is the clientName.
     *
     * The full URL is in a high cardinality value (which would be used by observability tools)
     */
    @Bean
    public ClientRequestObservationConvention nonUriClientRequestObservationConvention() {
        return new DefaultClientRequestObservationConvention() {
            @Override
            public KeyValues getLowCardinalityKeyValues(ClientRequestObservationContext context) {
                return KeyValues.of(method(context), status(context), clientName(context), exception(context), outcome(context));
            }
        };
    }
}
