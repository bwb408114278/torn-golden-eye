package pn.torn.goldeneye.base.torn;

import org.springframework.http.ResponseEntity;

/**
 * Torn Api 基类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.22
 */
public interface TornApi {
    /**
     * 发送Torn Api请求
     *
     * @param uri          请求路径
     * @param param        请求参数
     * @param responseType 响应类型
     * @return 响应数据
     */
    <T> ResponseEntity<T> sendRequest(String uri, TornReqParam param, Class<T> responseType);

    /**
     * 发送Torn Api请求, v2版本api
     *
     * @param uri          请求路径
     * @param param        请求参数
     * @param responseType 响应类型
     * @return 响应数据
     */
    <T> ResponseEntity<T> sendRequest(String uri, TornReqParamV2 param, Class<T> responseType);
}