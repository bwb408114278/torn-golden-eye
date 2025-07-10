package pn.torn.goldeneye.base;

import org.springframework.http.HttpMethod;

/**
 * Bot请求参数 - Http请求
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.06.22
 */
public interface BotHttpReqParam {
    /**
     * Http请求方式
     *
     * @return Http请求方式
     */
    HttpMethod method();

    /**
     * Http请求地址，不包含前缀
     *
     * @return Http请求地址
     */
    String uri();

    /**
     * 请求体数据
     *
     * @return 请求体
     */
    default Object body() {
        return null;
    }
}