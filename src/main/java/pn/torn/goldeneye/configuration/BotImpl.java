package pn.torn.goldeneye.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.base.exception.BizException;

/**
 * 机器人类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.06.22
 */
@Slf4j
class BotImpl implements Bot {
    /**
     * Web请求
     */
    private final RestClient restClient;

    public BotImpl(String serverAddr, String serverPort, String serverToken) {
        this.restClient = RestClient.builder()
                .baseUrl("http://" + serverAddr + ":" + serverPort)
                .defaultHeader(HttpHeaders.AUTHORIZATION, serverToken)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }

    @Override
    public <T> ResponseEntity<T> sendRequest(BotHttpReqParam param, Class<T> responseType) {
        try {
            RestClient.RequestBodySpec request = this.restClient
                    .method(param.method())
                    .uri(param.uri());

            if (param.body() != null) {
                request.body(param.body());
            }

            return request.retrieve().toEntity(responseType);
        } catch (Exception e) {
            log.error("发送Http Bot消息出错, 消息内容" + param.toString(), e);
            throw new BizException("发送Http Bot消息出错");
        }
    }
}