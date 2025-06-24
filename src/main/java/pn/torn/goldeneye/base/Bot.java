package pn.torn.goldeneye.base;

import org.springframework.http.ResponseEntity;

/**
 * 机器人基类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.06.22
 */
public interface Bot {
    /**
     * 发送Http请求
     */
    <T> ResponseEntity<T> sendRequest(BotReqParam param, Class<T> responseType);
}