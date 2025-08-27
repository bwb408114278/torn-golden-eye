package pn.torn.goldeneye.configuration;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.torn.TornApi;

/**
 * 通用配置类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.10
 */
@Configuration
@RequiredArgsConstructor
public class CommonConfiguration {
    @Value("${bot.server.addr}")
    private String serverAddr;
    @Value("${bot.server.port.http}")
    private String serverHttpPort;
    @Value("${bot.server.token}")
    private String serverToken;
    private final TornApiKeyConfig apiKeyConfig;

    @Bean
    public Bot buildHttpBot() {
        return new BotImpl(serverAddr, serverHttpPort, serverToken);
    }

    @Bean
    public TornApi buildTornApi() {
        return new TornApiImpl(this.apiKeyConfig);
    }

    @Bean
    public ThreadPoolTaskExecutor virtualThreadExecutor() {
        ThreadPoolTaskExecutor virtualThreadExecutor = new ThreadPoolTaskExecutor();
        virtualThreadExecutor.setThreadFactory(Thread.ofVirtual().name("virtual-", 0).factory());
        virtualThreadExecutor.setCorePoolSize(32);
        virtualThreadExecutor.setMaxPoolSize(256);
        virtualThreadExecutor.initialize();
        return virtualThreadExecutor;
    }
}