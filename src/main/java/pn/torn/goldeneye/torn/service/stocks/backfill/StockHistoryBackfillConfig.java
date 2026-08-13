package pn.torn.goldeneye.torn.service.stocks.backfill;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 股票历史回填配置 - 提供 Tornsy m1 接口专用 RestClient
 * <p>
 * 与 Torn API Key 池隔离：Tornsy 接口无 API Key，连接/响应超时默认 20 秒。
 *
 * @author Bai
 * @version 1.2.15
 * @since 2026.08.13
 */
@Configuration
public class StockHistoryBackfillConfig {

    /**
     * 连接/响应超时（秒）
     */
    private static final int TIMEOUT_SECONDS = 20;

    /**
     * Tornsy m1 接口专用 RestClient
     *
     * @return 配置了基础地址、超时与 Accept 头的 RestClient
     */
    @Bean
    public RestClient tornsyRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS));
        requestFactory.setReadTimeout(Duration.ofSeconds(TIMEOUT_SECONDS));
        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(TornsyStockHistoryClient.BASE_URL)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
