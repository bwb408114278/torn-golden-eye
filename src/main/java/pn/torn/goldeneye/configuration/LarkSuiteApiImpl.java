package pn.torn.goldeneye.configuration;

import com.lark.oapi.Client;
import com.lark.oapi.core.enums.BaseUrlEnum;
import com.lark.oapi.core.response.BaseResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.base.larksuite.LarkSuiteApi;
import pn.torn.goldeneye.base.larksuite.LarkSuiteManualReqParam;
import pn.torn.goldeneye.base.larksuite.LarkSuiteReqParam;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.configuration.property.larksuite.LarkSuiteProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.utils.JsonUtils;

import java.util.concurrent.TimeUnit;

/**
 * 飞书Api请求实现类
 *
 * @author Bai
 * @version 1.2.0
 * @since 2025.07.22
 */
@Slf4j
class LarkSuiteApiImpl implements LarkSuiteApi {
    private final Client client;
    private final Client selfClient;
    private final RestClient restClient;

    public LarkSuiteApiImpl(ProjectProperty projectProperty, LarkSuiteProperty larkSuiteProperty) {
        this.client = Client.newBuilder(larkSuiteProperty.getAppId(), larkSuiteProperty.getAppSecret())
                .openBaseUrl(BaseUrlEnum.FeiShu)
                .requestTimeout(30, TimeUnit.SECONDS)
                .logReqAtDebug(!BotConstants.ENV_PROD.equals(projectProperty.getEnv()))
                .build();
        this.selfClient = Client.newBuilder(larkSuiteProperty.getSelfAppId(), larkSuiteProperty.getSelfAppSecret())
                .openBaseUrl(BaseUrlEnum.FeiShu)
                .requestTimeout(30, TimeUnit.SECONDS)
                .logReqAtDebug(!BotConstants.ENV_PROD.equals(projectProperty.getEnv()))
                .build();
        this.restClient = RestClient.builder()
                .baseUrl("https://open.feishu.cn/open-apis")
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }

    @Override
    public <D, T extends BaseResponse<D>> D sendRequest(LarkSuiteManualReqParam param, String tenantToken,
                                                        Class<T> responseType) {
        try {
            String resp = restClient.method(param.method())
                    .uri(param.uri(), param.buildUrlParam())
                    .header("Authorization", "Bearer " + tenantToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(param.buildBodyParam())
                    .retrieve()
                    .body(String.class);
            if (!StringUtils.hasText(resp)) {
                throw new BizException("飞书请求返回的Body为空");
            }

            T response = JsonUtils.jsonToObj(resp, responseType);
            if (response == null) {
                throw new BizException("飞书请求返回结果转换失败");
            }

            if (!response.success()) {
                log.error("飞书请求出错, code:{}, msg:{}", response.getCode(), response.getMsg());
                return null;
            }

            return response.getData();
        } catch (Exception e) {
            log.error("飞书请求异常, ", e);
            return null;
        }
    }

    /**
     * 发送飞书请求
     */
    public <D, T extends BaseResponse<D>> D sendRequest(LarkSuiteReqParam<D, T> param) {
        return sendRequest(this.client, param);
    }

    @Override
    public <D, T extends BaseResponse<D>> D sendSelfRequest(LarkSuiteReqParam<D, T> param) {
        return sendRequest(this.selfClient, param);
    }

    /**
     * 发送飞书请求
     */
    private <D, T extends BaseResponse<D>> D sendRequest(Client client, LarkSuiteReqParam<D, T> param) {
        try {
            T resp = param.request(client);
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