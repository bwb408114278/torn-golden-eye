package pn.torn.goldeneye.configuration;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.larksuite.LarkSuiteApi;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.configuration.property.larksuite.LarkSuiteProperty;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;

/**
 * 通用配置类
 *
 * @author Bai
 * @version 0.3.0
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
    private final TornSettingFactionManager factionManager;
    private final ProjectProperty projectProperty;
    private final LarkSuiteProperty larkSuiteProperty;

    @Bean
    public Bot buildHttpBot() {
        return new BotImpl(serverAddr, serverHttpPort, serverToken, factionManager);
    }

    @Bean
    public TornApi buildTornApi() {
        return new TornApiImpl(this.apiKeyConfig);
    }

    @Bean
    public LarkSuiteApi buildLarkSuiteApi() {
        return new LarkSuiteApiImpl(projectProperty, larkSuiteProperty);
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