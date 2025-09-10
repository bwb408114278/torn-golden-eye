package pn.torn.goldeneye.base.larksuite;

import com.lark.oapi.Client;
import com.lark.oapi.core.response.BaseResponse;

/**
 * Torn请求参数
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.22
 */
public interface LarkSuiteReqParam<D, T extends BaseResponse<D>> {
    /**
     * 构建参数
     *
     * @param client 飞书客户端
     * @return 请求的数据类型
     * @throws Exception 飞书异常
     */
    T request(Client client) throws Exception;
}