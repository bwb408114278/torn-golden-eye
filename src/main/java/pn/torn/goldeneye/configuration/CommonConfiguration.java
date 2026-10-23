package pn.torn.goldeneye.configuration;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.larksuite.LarkSuiteApi;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.configuration.property.larksuite.LarkSuiteProperty;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.manager.torn.TornApiKeySecurityNoticeManager;

import java.time.Duration;

/**
 * 通用配置类
 *
 * @author Bai
 * @version 1.9.3
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
    public TornApi buildTornApi(TornApiKeySecurityNoticeManager apiKeySecurityNoticeManager,
                                ClientHttpRequestFactory tornHttpRequestFactory) {
        return new TornApiImpl(this.apiKeyConfig, apiKeySecurityNoticeManager, tornHttpRequestFactory);
    }

    /**
     * Torn API 专用请求工厂：显式声明连接与响应读取超时，避免外部依赖故障时单次尝试无限等待。
     * <p>
     * 读取超时是本次故障形态下的有效闸门：对端接受连接后不再返回任何数据时，读取超时到点即抛出
     * ReadTimeoutException。该类型实现 {@link org.springframework.context.SmartLifecycle}，必须作为
     * 容器Bean由Spring驱动start/stop；禁止在业务类内手工构造。两个版本的RestClient共用同一实例，
     * 保证超时口径与连接池只有一份。
     *
     * @return Torn API 专用请求工厂
     */
    @Bean
    public ClientHttpRequestFactory tornHttpRequestFactory() {
        ReactorClientHttpRequestFactory factory = new ReactorClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(TornConstants.HTTP_CONNECT_TIMEOUT_MS));
        factory.setReadTimeout(Duration.ofMillis(TornConstants.HTTP_READ_TIMEOUT_MS));
        return factory;
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