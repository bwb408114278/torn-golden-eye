package pn.torn.goldeneye.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.base.torn.TornReqParamV2;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.utils.JsonUtils;

import java.util.List;

/**
 * Torn Api请求实现类
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.07.22
 */
@Slf4j
class TornApiImpl implements TornApi {
    private final TornApiKeyConfig apiKeyConfig;
    private final RestClient restClientV2;
    // 定义最大重试次数
    private static final int MAX_RETRIES = 3;
    // 定义Key无效的错误码
    private static final int INVALID_KEY_ERROR_CODE = 2;
    private static final int KEY_OWNER_INACTIVE = 13;
    private static final int KEY_PAUSED_ERROR_CODE = 18;

    public TornApiImpl(TornApiKeyConfig apiKeyConfig) {
        this.apiKeyConfig = apiKeyConfig;
        this.restClientV2 = RestClient.builder()
                .baseUrl(TornConstants.BASE_URL_V2)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
        apiKeyConfig.reloadKeyData();
    }

    @Override
    public <T> T sendRequest(TornReqParamV2 param, Class<T> responseType) {
        return executeRequest(param, null, responseType);
    }

    @Override
    public <T> T sendRequest(long factionId, TornReqParamV2 param, Class<T> responseType) {
        return executeRequest(param, factionId, responseType);
    }

    @Override
    public <T> T sendRequest(TornReqParamV2 param, TornApiKeyDO apiKey, Class<T> responseType) {
        try {
            UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(param.uri());
            MultiValueMap<String, String> paramMap = param.buildReqParam();
            paramMap.put("comment", List.of("golden-eye"));
            uriBuilder.queryParams(paramMap);

            String finalUri = uriBuilder.encode().build().toUriString();
            RestClient.RequestBodySpec reqSpec = this.restClientV2.method(HttpMethod.GET).uri(finalUri);
            if (apiKey != null) {
                reqSpec = reqSpec.header("Authorization", "ApiKey " + apiKey.getApiKey());
            }

            ResponseEntity<String> entity = sendRequest(reqSpec, 0);
            T response = handleResponse(param, entity, responseType);
            apiKeyConfig.returnKey(apiKey);
            return response;
        } catch (BizException e) {
            if (e.getCode() == BotConstants.EX_INVALID_KEY && apiKey != null) {
                log.warn("调用者指定的API Key(ID:{}) 已失效，将作废该Key并向上抛出异常。", apiKey.getId());
                apiKeyConfig.invalidateKey(apiKey);
            } else {
                apiKeyConfig.returnKey(apiKey);
            }
            throw e;
        } catch (Exception e) {
            log.error("使用指定Key请求Torn Api V2时出错", e);
            apiKeyConfig.returnKey(apiKey);
            return null;
        }
    }

    private <T> T executeRequest(TornReqParamV2 param, Long factionId, Class<T> responseType) {
        for (int i = 0; i < MAX_RETRIES; i++) {
            TornApiKeyDO apiKey = (factionId == null)
                    ? apiKeyConfig.getEnableKey()
                    : apiKeyConfig.getFactionKey(factionId, param.needFactionAccess());

            if (apiKey == null) {
                log.warn("无法获取可用的API Key (帮派ID: {}), 终止请求。", factionId);
                return null;
            }

            try {
                UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(param.uri());
                MultiValueMap<String, String> paramMap = param.buildReqParam();
                paramMap.put("comment", List.of("golden-eye"));
                uriBuilder.queryParams(paramMap);

                String finalUri = uriBuilder.encode().build().toUriString();
                RestClient.RequestBodySpec reqSpec = this.restClientV2.method(HttpMethod.GET).uri(finalUri)
                        .header("Authorization", "ApiKey " + apiKey.getApiKey());

                ResponseEntity<String> entity = sendRequest(reqSpec, 0);
                T result = handleResponse(param, entity, responseType);

                apiKeyConfig.returnKey(apiKey);
                return result;
            } catch (BizException e) {
                if (e.getCode() == BotConstants.EX_INVALID_KEY) {
                    log.warn("API Key(ID:{}) 已失效。正在移除并进行第 {}/{} 次重试...", apiKey.getId(), i + 1, MAX_RETRIES);
                    apiKeyConfig.invalidateKey(apiKey);
                    // 继续下一次循环，获取新Key
                } else {
                    apiKeyConfig.returnKey(apiKey);
                    throw e;
                }
            } catch (Exception e) {
                log.error("请求Torn Api V2时发生未知错误", e);
                apiKeyConfig.returnKey(apiKey);
                return null;
            }
        }
        log.error("Torn请求 {} 在因Key失效重试 {} 次后仍然失败。", param.uri(), MAX_RETRIES);
        return null;
    }

    /**
     * 发送请求，失败后会进行3次重试
     */
    private ResponseEntity<String> sendRequest(RestClient.RequestBodySpec reqSpec, int retryCount) {
        try {
            return reqSpec.retrieve().toEntity(String.class);
        } catch (Exception e) {
            retryCount++;
            log.warn("第{}次请求Torn Api V2出错", retryCount, e);
            if (retryCount > 3) {
                return null;
            } else {
                return sendRequest(reqSpec, retryCount);
            }
        }
    }

    /**
     * 处理响应体
     */
    private <T> T handleResponse(TornReqParamV2 param, ResponseEntity<String> entity, Class<T> responseType) {
        try {
            if (entity == null || entity.getBody() == null || entity.getBody().isEmpty()) {
                return null;
            }

            if (JsonUtils.existsNode(entity.getBody(), "error")) {
                log.error("Torn Api报错, uri: {}, response: {}", param.uri(), entity.getBody());
                JsonNode node = JsonUtils.getNode(entity.getBody(), "error.code");
                // 无效的Key
                if (node != null && (node.asInt() == INVALID_KEY_ERROR_CODE ||
                        node.asInt() == KEY_OWNER_INACTIVE ||
                        node.asInt() == KEY_PAUSED_ERROR_CODE)) {
                    throw new BizException(BotConstants.EX_INVALID_KEY, "无效的Key");
                } else {
                    return null;
                }
            }

            return JsonUtils.jsonToObj(entity.getBody(), responseType);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析Torn API响应时发生未知错误", e);
            return null;
        }
    }
}