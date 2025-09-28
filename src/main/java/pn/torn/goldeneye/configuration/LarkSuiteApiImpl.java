package pn.torn.goldeneye.configuration;

import com.lark.oapi.Client;
import com.lark.oapi.core.enums.BaseUrlEnum;
import com.lark.oapi.core.response.BaseResponse;
import lombok.extern.slf4j.Slf4j;
import pn.torn.goldeneye.base.larksuite.LarkSuiteApi;
import pn.torn.goldeneye.base.larksuite.LarkSuiteReqParam;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.configuration.property.larksuite.LarkSuiteProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;

import java.util.concurrent.TimeUnit;

/**
 * Torn Api请求实现类
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.07.22
 */
@Slf4j
class LarkSuiteApiImpl implements LarkSuiteApi {
    private final Client client;

    public LarkSuiteApiImpl(ProjectProperty projectProperty, LarkSuiteProperty larkSuiteProperty) {
        this.client = Client.newBuilder(larkSuiteProperty.getAppId(), larkSuiteProperty.getAppSecret())
                .openBaseUrl(BaseUrlEnum.FeiShu)
                .requestTimeout(30, TimeUnit.SECONDS)
                .logReqAtDebug(!BotConstants.ENV_PROD.equals(projectProperty.getEnv()))
                .build();
    }

    /**
     * 发送飞书请求
     */
    public <D, T extends BaseResponse<D>> D sendRequest(LarkSuiteReqParam<D, T> param) {
        try {
            T resp = param.request(this.client);
            if (!resp.success()) {
                log.error("飞书请求出错, code:{}, msg:{}, reqId:{}",
                        resp.getCode(), resp.getMsg(), resp.getRequestId());
                return null;
            }

            return resp.getData();
        } catch (Exception e) {
            log.error("飞书请求异常, ", e);
            return null;
        }
    }
}