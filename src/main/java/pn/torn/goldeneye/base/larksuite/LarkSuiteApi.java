package pn.torn.goldeneye.base.larksuite;

import com.lark.oapi.core.response.BaseResponse;

/**
 * 飞书Api基类
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.04
 */
public interface LarkSuiteApi {
    /**
     * 发送飞书请求
     *
     * @param param 请求参数
     * @param <D>   数据类型
     * @param <T>   参数类型
     * @return 请求数据
     */
    <D, T extends BaseResponse<D>> D sendRequest(LarkSuiteReqParam<D, T> param);
}