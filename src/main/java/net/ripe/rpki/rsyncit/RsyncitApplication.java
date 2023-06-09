package net.ripe.rpki.rsyncit;

import io.netty.channel.nio.NioEventLoopGroup;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;
import net.ripe.rpki.rsyncit.util.http.WebClientBuilderFactory;
import net.ripe.rpki.rsyncit.config.AppConfig;

import java.util.Properties;

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
    public WebClientBuilderFactory webclientConfigurer(WebClient.Builder baseBuilder, AppConfig appConfig) {
        // Explicit event loop is required for custom DnsNameResolverBuilder
        NioEventLoopGroup group = new NioEventLoopGroup(1);

        return new WebClientBuilderFactory(group, baseBuilder, "rsyncit %s".formatted(appConfig.getInfo().gitCommitId()));
    }

}
