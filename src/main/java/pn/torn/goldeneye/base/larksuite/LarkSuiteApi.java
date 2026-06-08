package pn.torn.goldeneye.base.larksuite;

import com.lark.oapi.core.response.BaseResponse;

/**
 * 飞书Api基类
 *
 * @author Bai
 * @version 1.2.0
 * @since 2025.09.04
 */
public interface LarkSuiteApi {
    /**
     * 发送飞书Api请求
     *
     * @param param        请求参数
     * @param tenantToken  Tenant Token
     * @param responseType 响应类型
     * @return 响应数据
     */
    <D, T extends BaseResponse<D>> D sendRequest(LarkSuiteManualReqParam param, String tenantToken,
                                                 Class<T> responseType);

    /**
     * 发送飞书请求
     *
     * @param param 请求参数
     * @param <D>   数据类型
     * @param <T>   参数类型
     * @return 请求数据
     */
    <D, T extends BaseResponse<D>> D sendRequest(LarkSuiteReqParam<D, T> param);

    /**
     * 发送自建应用飞书请求
     *
     * @param param 请求参数
     * @param <D>   数据类型
     * @param <T>   参数类型
     * @return 请求数据
     */
    <D, T extends BaseResponse<D>> D sendSelfRequest(LarkSuiteReqParam<D, T> param);
}