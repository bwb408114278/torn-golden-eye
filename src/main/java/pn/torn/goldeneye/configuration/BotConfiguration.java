package pn.torn.goldeneye.configuration;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pn.torn.goldeneye.base.Bot;

/**
 * 机器人配置类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.06.22
 */
@Configuration
public class BotConfiguration {
    @Value("${bot.server.addr}")
    private String serverAddr;
    @Value("${bot.server.port.http}")
    private String serverHttpPort;
    @Value("${bot.server.port.socket}")
    private String serverSocketPort;
    @Value("${bot.server.token}")
    private String serverToken;

    @Bean
    public Bot buildHttpBot() {
        return new BotImpl(serverAddr, serverHttpPort, serverToken);
    }

    @PostConstruct
    public void buildSocketBot() throws Exception {
        BotSocketClient.connect("ws://" + serverAddr + ":" + serverSocketPort + "/?access_token=" + serverToken);
    }
}