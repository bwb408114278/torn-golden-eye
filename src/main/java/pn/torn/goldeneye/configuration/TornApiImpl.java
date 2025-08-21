package pn.torn.goldeneye.configuration;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.base.torn.TornReqParam;
import pn.torn.goldeneye.base.torn.TornReqParamV2;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.utils.JsonUtils;

/**
 * Torn Api请求实现类
 *
 * @author Bai
 * @version 0.1.1
 * @since 2025.07.22
 */
@Slf4j
class TornApiImpl implements TornApi {
    private final TornApiKeyConfig apiKeyConfig;

    /**
     * Web请求
     */
    private final RestClient restClient;
    /**
     * Web请求, api v2版本
     */
    private final RestClient restClientV2;

    public TornApiImpl(TornApiKeyConfig apiKeyConfig) {
        this.apiKeyConfig = apiKeyConfig;
        this.restClient = RestClient.builder()
                .baseUrl(TornConstants.BASE_URL)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();

        this.restClientV2 = RestClient.builder()
                .baseUrl(TornConstants.BASE_URL_V2)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();

        apiKeyConfig.refreshKeyData();
    }

    @Override
    public <T> T sendRequest(String uri, TornReqParam param, Class<T> responseType) {
        TornApiKeyDO apiKey = null;
        try {
            apiKey = apiKeyConfig.getEnableKey(param.needFactionAccess());
            String uriWithParam = uri + "/" +
                    (param.getId() == null ? "" : param.getId()) +
                    "?selections=" + param.getSection() +
                    "&key=" + apiKey.getApiKey();

            ResponseEntity<String> entity = this.restClient
                    .method(HttpMethod.GET)
                    .uri(uriWithParam)
                    .retrieve()
                    .toEntity(String.class);

            return handleResponse(entity, responseType);
        } catch (Exception e) {
            log.error("请求Torn Api出错", e);
            return null;
        } finally {
            apiKeyConfig.returnKeyToQueue(apiKey);
        }
    }

    @Override
    public <T> T sendRequest(TornReqParamV2 param, Class<T> responseType) {
        return sendRequest(param, apiKeyConfig.getEnableKey(param.needFactionAccess()), responseType);
    }

    @Override
    public <T> T sendRequest(TornReqParamV2 param, TornApiKeyDO apiKey, Class<T> responseType) {
        try {
            UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(param.uri());
            MultiValueMap<String, String> paramMap = param.buildReqParam();
            if (!MapUtils.isEmpty(paramMap)) {
                uriBuilder.queryParams(paramMap);
            }

            String finalUri = uriBuilder.encode().build().toUriString();
            RestClient.RequestBodySpec reqSpec = this.restClientV2
                    .method(HttpMethod.GET)
                    .uri(finalUri);
            if (apiKey != null) {
                reqSpec = reqSpec.header("Authorization", "ApiKey " + apiKey.getApiKey());
            }
            ResponseEntity<String> entity = reqSpec.retrieve().toEntity(String.class);

            return handleResponse(entity, responseType);
        } catch (Exception e) {
            log.error("请求Torn Api V2出错", e);
            return null;
        } finally {
            apiKeyConfig.returnKeyToQueue(apiKey);
        }
    }

    /**
     * 处理响应体
     */
    private <T> T handleResponse(ResponseEntity<String> entity, Class<T> responseType) {
        try {
            if (entity.getBody() == null || entity.getBody().isEmpty()) {
                return null;
            }

            if (JsonUtils.existsNode(entity.getBody(), "error")) {
                throw new BizException("Torn Api报错: " + entity.getBody());
            }

            return JsonUtils.jsonToObj(entity.getBody(), responseType);
        } catch (Exception e) {
            return null;
        }
    }
}