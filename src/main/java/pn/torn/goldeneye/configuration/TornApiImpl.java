package pn.torn.goldeneye.configuration;

import lombok.extern.slf4j.Slf4j;
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
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.key.TornApiErrorCodeEnum;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.utils.JsonUtils;

import java.util.List;

/**
 * Torn Api请求实现类
 *
 * @author Bai
 * @version 1.0.0
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
    // 定义最大重试次数
    private static final int MAX_RETRIES = 3;
    private static final int HTTP_RETRY_COUNT = 3;

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
        apiKeyConfig.reloadKeyData();
    }

    @Override
    public <T> T sendRequest(TornReqParam param, TornApiKeyDO apiKey, Class<T> responseType) {
        TornApiRequestExecutor executor = key -> executeV1Request(param, key);
        return executeWithKeyManagement(executor, apiKey, param.uri(), responseType, false);
    }

    @Override
    public <T> T sendRequest(TornReqParamV2 param, Class<T> responseType) {
        return sendRequest(param, null, responseType);
    }

    @Override
    public <T> T sendRequest(long factionId, TornReqParamV2 param, Class<T> responseType) {
        TornApiRequestExecutor executor = key -> executeV2Request(param, key);
        return executeWithRetry(executor, param.uri(), factionId, param.needFactionAccess(), responseType);
    }

    @Override
    public <T> T sendRequest(TornReqParamV2 param, TornApiKeyDO apiKey, Class<T> responseType) {
        TornApiRequestExecutor executor = key -> executeV2Request(param, key);
        return executeWithKeyManagement(executor, apiKey, param.uri(), responseType, false);
    }

    /**
     * 执行 V1 API 请求
     */
    private ResponseEntity<String> executeV1Request(TornReqParam param, TornApiKeyDO apiKey) {
        String uriWithParam = param.uri() + "/" +
                (param.getId() == null ? "" : param.getId()) +
                "?selections=" + param.getSection() +
                "&key=" + apiKey.getApiKey() +
                "&comment=golden-eye";
        return restClient.method(HttpMethod.GET)
                .uri(uriWithParam)
                .retrieve()
                .toEntity(String.class);
    }

    /**
     * 执行 V2 API 请求
     */
    private ResponseEntity<String> executeV2Request(TornReqParamV2 param, TornApiKeyDO apiKey) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(param.uri());
        MultiValueMap<String, String> paramMap = param.buildReqParam();
        paramMap.put("comment", List.of("golden-eye"));
        uriBuilder.queryParams(paramMap);
        String finalUri = uriBuilder.encode().build().toUriString();
        RestClient.RequestBodySpec reqSpec = restClientV2.method(HttpMethod.GET).uri(finalUri);

        if (apiKey != null) {
            reqSpec = reqSpec.header("Authorization", "ApiKey " + apiKey.getApiKey());
        }
        return executeWithHttpRetry(reqSpec, 0);
    }

    /**
     * 带重试机制的请求执行（针对 Key 失效）
     */
    private <T> T executeWithRetry(TornApiRequestExecutor executor, String uri,
                                   long factionId, boolean needFactionAccess, Class<T> responseType) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            TornApiKeyDO apiKey = (factionId == 0L)
                    ? apiKeyConfig.getEnableKey()
                    : apiKeyConfig.getFactionKey(factionId, needFactionAccess);
            if (apiKey == null) {
                log.warn("无法获取可用的API Key (帮派ID: {}), 终止请求。", factionId);
                return null;
            }

            T result = executeWithKeyManagement(executor, apiKey, uri, responseType, true);
            if (result != null) {
                return result;
            }
            log.warn("第 {}/{} 次重试...", attempt + 1, MAX_RETRIES);
        }
        log.error("Torn请求 {} 在因Key失效重试 {} 次后仍然失败。", uri, MAX_RETRIES);
        return null;
    }

    /**
     * 核心执行方法：统一的 Key 管理和异常处理
     */
    private <T> T executeWithKeyManagement(TornApiRequestExecutor executor, TornApiKeyDO apiKey, String uri,
                                           Class<T> responseType, boolean isRetryContext) {
        try {
            ResponseEntity<String> response = executor.execute(apiKey);
            TornApiRequestContext context = new TornApiRequestContext(uri, apiKey, response);

            T result = processResponse(context, responseType);
            apiKeyConfig.returnKey(apiKey);
            return result;
        } catch (BizException e) {
            handleBizException(e, apiKey, isRetryContext);
            throw e;
        } catch (Exception e) {
            log.error("请求Torn API时发生未知错误", e);
            apiKeyConfig.returnKey(apiKey);
            return null;
        }
    }

    /**
     * 处理业务异常
     */
    private void handleBizException(BizException e, TornApiKeyDO apiKey, boolean isRetryContext) {
        if (e.getCode() == BotConstants.EX_INVALID_KEY && apiKey != null) {
            if (!isRetryContext) {
                log.warn("调用者指定的API Key(ID:{}) 已失效，将作废该Key并向上抛出异常。", apiKey.getId());
            }
            apiKeyConfig.invalidateKey(apiKey);
        } else {
            apiKeyConfig.returnKey(apiKey);
        }
    }

    /**
     * HTTP 层面的重试（网络错误等）
     */
    private ResponseEntity<String> executeWithHttpRetry(RestClient.RequestBodySpec reqSpec, int retryCount) {
        try {
            return reqSpec.retrieve().toEntity(String.class);
        } catch (Exception e) {
            retryCount++;
            log.warn("第{}次请求Torn API出错", retryCount, e);
            if (retryCount >= HTTP_RETRY_COUNT) {
                return null;
            }
            return executeWithHttpRetry(reqSpec, retryCount);
        }
    }

    /**
     * 处理响应并解析
     */
    private <T> T processResponse(TornApiRequestContext context, Class<T> responseType) {
        ResponseEntity<String> response = context.response();
        if (response == null) {
            return null;
        }

        String body = response.getBody();
        if (body == null || body.isEmpty()) {
            return null;
        }
        if (context.hasError()) {
            handleApiError(context);
            return null;
        }
        return JsonUtils.jsonToObj(body, responseType);
    }

    /**
     * 统一处理 API 错误
     */
    private void handleApiError(TornApiRequestContext context) {
        Integer errorCode = context.getErrorCode();
        if (errorCode == null) {
            log.error("Torn API报错但无法解析错误码, uri: {}, response: {}",
                    context.uri(), context.response().getBody());
            return;
        }

        TornApiErrorCodeEnum apiError = TornApiErrorCodeEnum.fromCode(errorCode);
        String responseBody = context.response().getBody();

        switch (apiError) {
            case INVALID_KEY, KEY_OWNER_FJ, KEY_OWNER_INACTIVE, KEY_PAUSED -> {
                log.warn("Torn API Key错误 [错误码:{}], uri: {}, Key ID: {}",
                        errorCode, context.uri(), context.apiKey().getId());
                if (apiError.isShouldInvalidateKey()) {
                    throw new BizException(BotConstants.EX_INVALID_KEY, apiError.getMessage());
                }
            }
            case TOO_MANY_REQUESTS -> log.error("Torn API请求过于频繁 [错误码:{}], uri: {}, Key ID: {}, User ID: {}",
                    errorCode, context.uri(), context.apiKey().getId(), context.apiKey().getUserId());
            default -> log.error("Torn API报错 [错误码:{}], uri: {}, Key ID: {}, response: {}",
                    errorCode, context.uri(), context.apiKey().getId(), responseBody);
        }
    }
}