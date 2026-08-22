package pn.torn.goldeneye.base.torn;

import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;

/**
 * Torn Api 基类
 *
 * @author Bai
 * @version 1.4.0
 * @since 2025.07.22
 */
public interface TornApi {
    /**
     * 发送Torn Api请求
     *
     * @param param        请求参数
     * @param apiKey       指定Key
     * @param responseType 响应类型
     * @return 响应数据
     */
    <T> T sendRequest(TornReqParam param, TornApiKeyDO apiKey, Class<T> responseType);

    /**
     * 发送 Torn API v1 请求，由基础设施层选择并管理 API Key，业务调用方无需传递 Key。
     *
     * @param param        v1 请求参数
     * @param responseType 响应类型
     * @param <T>          响应类型
     * @return 指定类型的响应数据
     */
    <T> T sendRequest(TornReqParam param, Class<T> responseType);

    /**
     * 发送Torn Api请求, v2版本api
     *
     * @param param        请求参数
     * @param responseType 响应类型
     * @return 响应数据
     */
    <T> T sendRequest(TornReqParamV2 param, Class<T> responseType);

    /**
     * 发送Torn Api请求, v2版本api
     *
     * @param factionId    帮派ID
     * @param param        请求参数
     * @param responseType 响应类型
     * @return 响应数据
     */
    <T> T sendRequest(long factionId, TornReqParamV2 param, Class<T> responseType);

    /**
     * 发送Torn Api请求, v2版本api
     *
     * @param param        请求参数
     * @param apiKey       指定的ApiKey
     * @param responseType 响应类型
     * @return 响应数据
     */
    <T> T sendRequest(TornReqParamV2 param, TornApiKeyDO apiKey, Class<T> responseType);
}
