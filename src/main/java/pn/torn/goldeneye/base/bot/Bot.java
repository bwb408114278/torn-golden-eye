package pn.torn.goldeneye.base.bot;

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
     *
     * @param param        HTTP请求参数
     * @param responseType 响应类型
     * @return 响应数据
     */
    <T> ResponseEntity<T> sendRequest(BotHttpReqParam param, Class<T> responseType);
}