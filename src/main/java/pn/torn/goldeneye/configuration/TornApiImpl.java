package pn.torn.goldeneye.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
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
import pn.torn.goldeneye.torn.manager.torn.TornApiKeySecurityNoticeManager;
import pn.torn.goldeneye.utils.JsonUtils;

import java.time.Duration;
import java.util.List;

/**
 * Torn Api请求实现类
 * <p>
 * 请求的耗时边界由两层重试共同构成：Key轮换与单Key内的HTTP重试共用同一份时间预算
 * （{@link TornConstants#HTTP_RETRY_BUDGET_MS}），预算耗尽即放弃，避免外部依赖故障时
 * 单次逻辑请求出现"轮换次数 x HTTP次数 x 单次超时"的乘法放大。
 *
 * @author Bai
 * @version 1.9.3
 * @since 2025.07.22
 */
@Slf4j
class TornApiImpl implements TornApi {
    private final TornApiKeyConfig apiKeyConfig;
    private final TornApiKeySecurityNoticeManager apiKeySecurityNoticeManager;
    /**
     * Web请求
     */
    private final RestClient restClient;
    /**
     * Web请求, api v2版本
     */
    private final RestClient restClientV2;

    /**
     * 创建Torn Api请求实现。
     *
     * @param apiKeyConfig                API Key配置
     * @param apiKeySecurityNoticeManager API Key安全通知管理器
     * @param requestFactory              带连接/读取/交换超时的请求工厂，两个版本共用同一实例
     */
    public TornApiImpl(TornApiKeyConfig apiKeyConfig,
                       TornApiKeySecurityNoticeManager apiKeySecurityNoticeManager,
                       ClientHttpRequestFactory requestFactory) {
        this.apiKeyConfig = apiKeyConfig;
        this.apiKeySecurityNoticeManager = apiKeySecurityNoticeManager;
        this.restClient = RestClient.builder()
                .baseUrl(TornConstants.BASE_URL)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .requestFactory(requestFactory)
                .build();
        this.restClientV2 = RestClient.builder()
                .baseUrl(TornConstants.BASE_URL_V2)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .requestFactory(requestFactory)
                .build();
        apiKeyConfig.reloadKeyData();
    }

    @Override
    public <T> T sendRequest(TornReqParam param, TornApiKeyDO apiKey, Class<T> responseType) {
        TornApiRequestExecutor executor = key -> executeV1Request(param, key);
        return executeWithKeyManagement(executor, apiKey, param.uri(), responseType, false, 0L).result();
    }

    @Override
    public <T> T sendRequest(TornReqParam param, Class<T> responseType) {
        TornApiRequestExecutor executor = key -> executeV1Request(param, key);
        return executeWithRetry(executor, param.uri(), 0L, false, responseType, new RequestBudget());
    }

    @Override
    public <T> T sendRequest(TornReqParamV2 param, Class<T> responseType) {
        return sendRequest(0L, param, responseType);
    }

    @Override
    public <T> T sendRequest(long factionId, TornReqParamV2 param, Class<T> responseType) {
        RequestBudget budget = new RequestBudget();
        TornApiRequestExecutor executor = key -> executeV2Request(param, key, budget);
        return executeWithRetry(executor, param.uri(), factionId, param.needFactionAccess(), responseType, budget);
    }

    @Override
    public <T> T sendRequest(TornReqParamV2 param, TornApiKeyDO apiKey, Class<T> responseType) {
        RequestBudget budget = new RequestBudget();
        TornApiRequestExecutor executor = key -> executeV2Request(param, key, budget);
        return executeWithKeyManagement(executor, apiKey, param.uri(), responseType, false, 0L).result();
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
    private ResponseEntity<String> executeV2Request(TornReqParamV2 param, TornApiKeyDO apiKey, RequestBudget budget) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(param.uri());
        MultiValueMap<String, String> paramMap = param.buildReqParam();
        paramMap.put("comment", List.of("golden-eye"));
        uriBuilder.queryParams(paramMap);
        String finalUri = uriBuilder.encode().build().toUriString();
        RestClient.RequestBodySpec reqSpec = restClientV2.method(HttpMethod.GET).uri(finalUri);

        if (apiKey != null) {
            reqSpec = reqSpec.header("Authorization", "ApiKey " + apiKey.getApiKey());
        }
        return executeWithHttpRetry(reqSpec, budget);
    }

    /**
     * 带 Key 轮换的请求执行：每轮按时间预算与 HTTP 尝试次数收敛，预算耗尽即放弃。
     * <p>
     * Key轮换与HTTP重试共用同一份 {@link RequestBudget}，禁止各层各给一份预算，
     * 否则层数乘以每层预算会重新变成乘法放大。
     *
     * @param executor          单次请求执行器
     * @param uri               请求路径（日志定位用）
     * @param factionId         帮派ID，0表示请求本身与帮派无关
     * @param needFactionAccess 是否需要帮派权限
     * @param responseType      响应类型
     * @param budget            本次逻辑请求的重试预算
     * @param <T>               响应类型
     * @return 响应对象，重试耗尽或无可用的Key时返回null
     */
    private <T> T executeWithRetry(TornApiRequestExecutor executor, String uri, long factionId,
                                   boolean needFactionAccess, Class<T> responseType, RequestBudget budget) {
        for (int rotation = 0; rotation < TornConstants.HTTP_KEY_ROTATION_COUNT; rotation++) {
            if (budget.exhausted()) {
                break;
            }
            TornApiKeyDO apiKey = (factionId == 0L)
                    ? apiKeyConfig.getEnableKey()
                    : apiKeyConfig.getFactionKey(factionId, needFactionAccess);
            if (apiKey == null) {
                log.warn("无法获取可用的API Key (帮派ID: {}), 终止请求。", factionId);
                return null;
            }

            ApiResponseResult<T> responseResult = executeWithKeyManagement(executor, apiKey, uri,
                    responseType, true, factionId);
            if (!responseResult.shouldRetry()) {
                return responseResult.result();
            }
            budget.recordKeyRotation();
            log.warn("第 {}/{} 轮Key轮换重试, uri={}, elapsedMs={}",
                    rotation + 1, TornConstants.HTTP_KEY_ROTATION_COUNT, uri, budget.elapsedMillis());
            if (rotation + 1 >= TornConstants.HTTP_KEY_ROTATION_COUNT || !budget.pause()) {
                break;
            }
        }
        log.error("Torn请求 {} 重试后仍然失败, keyRotationCount={}, httpAttemptCount={}, elapsedMs={}, lastCause={}",
                uri, budget.keyRotationCount(), budget.httpAttemptCount(), budget.elapsedMillis(),
                budget.lastCauseName());
        return null;
    }

    /**
     * 核心执行方法：统一的 Key 管理和异常处理
     */
    private <T> ApiResponseResult<T> executeWithKeyManagement(TornApiRequestExecutor executor, TornApiKeyDO apiKey,
                                                              String uri, Class<T> responseType,
                                                              boolean isRetryContext, long factionId) {
        try {
            ResponseEntity<String> response = executor.execute(apiKey);
            TornApiRequestContext context = new TornApiRequestContext(uri, apiKey, response);

            ApiResponseResult<T> result = processResponse(context, responseType, factionId);
            apiKeyConfig.returnKey(apiKey);
            return result;
        } catch (BizException e) {
            handleBizException(e, apiKey, isRetryContext);
            throw e;
        } catch (Exception e) {
            log.error("请求Torn API时发生未知错误", e);
            apiKeyConfig.returnKey(apiKey);
            return ApiResponseResult.retry();
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
     * HTTP 层面的重试（网络错误等）：在尝试次数与时间预算内自愈，超出即放弃。
     *
     * @param reqSpec 待执行的请求
     * @param budget  本次逻辑请求的重试预算
     * @return 响应实体；尝试次数用尽或预算耗尽时返回null
     */
    private ResponseEntity<String> executeWithHttpRetry(RestClient.RequestBodySpec reqSpec, RequestBudget budget) {
        for (int attempt = 1; attempt <= TornConstants.HTTP_ATTEMPT_COUNT; attempt++) {
            if (budget.exhausted()) {
                return null;
            }
            budget.recordHttpAttempt();
            try {
                return reqSpec.retrieve().toEntity(String.class);
            } catch (Exception e) {
                budget.recordFailure(e);
                log.warn("第{}次请求Torn API出错, httpAttemptCount={}, elapsedMs={}",
                        attempt, budget.httpAttemptCount(), budget.elapsedMillis(), e);
                if (attempt == TornConstants.HTTP_ATTEMPT_COUNT || !budget.pause()) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * 处理响应并解析
     */
    private <T> ApiResponseResult<T> processResponse(TornApiRequestContext context, Class<T> responseType,
                                                     long factionId) {
        ResponseEntity<String> response = context.response();
        if (response == null) {
            return ApiResponseResult.retry();
        }

        String body = response.getBody();
        if (body == null || body.isEmpty()) {
            return ApiResponseResult.retry();
        }
        if (context.hasError()) {
            handleApiError(context, factionId != 0L);
            return context.getErrorCode() == TornApiErrorCodeEnum.ID_ENTITY_RELATION_ERROR.getCode()
                    ? ApiResponseResult.stop()
                    : ApiResponseResult.retry();
        }
        return ApiResponseResult.success(JsonUtils.jsonToObj(body, responseType));
    }

    /**
     * 统一处理 API 错误
     */
    private void handleApiError(TornApiRequestContext context, boolean factionRequest) {
        Integer errorCode = context.getErrorCode();
        if (errorCode == null) {
            log.error("Torn API报错但无法解析错误码, uri: {}, response: {}",
                    context.uri(), context.response().getBody());
            return;
        }

        TornApiErrorCodeEnum apiError = TornApiErrorCodeEnum.fromCode(errorCode);
        String responseBody = context.response().getBody();

        switch (apiError) {
            case ID_ENTITY_RELATION_ERROR -> {
                if (factionRequest) {
                    apiKeyConfig.removeFromFactionPool(context.apiKey());
                    log.info("Torn API请求的ID与实体关系不符，已将Key移出当前帮派池，结束本次请求 [错误码:{}], uri: {}",
                            errorCode, context.uri());
                } else {
                    log.info("Torn API请求的ID与实体关系不符，结束本次请求 [错误码:{}], uri: {}",
                            errorCode, context.uri());
                }
            }
            case INVALID_KEY, KEY_OWNER_FJ, KEY_OWNER_INACTIVE, KEY_PAUSED -> {
                log.warn("Torn API Key错误 [错误码:{}], uri: {}, Key ID: {}",
                        errorCode, context.uri(), context.apiKey().getId());
                if (apiError.isShouldInvalidateKey()) {
                    throw new BizException(BotConstants.EX_INVALID_KEY, apiError.getMessage());
                }
            }
            case TOO_MANY_REQUESTS -> apiKeySecurityNoticeManager.noticeApiKeySecurity(context.apiKey(), context.uri());
            default -> log.error("Torn API报错 [错误码:{}], uri: {}, Key ID: {}, response: {}",
                    errorCode, context.uri(), context.apiKey().getId(), responseBody);
        }
    }

    /**
     * 单次逻辑请求的重试预算与尝试计数。
     * <p>
     * 预算自 {@code sendRequest} 入口开始计时，Key轮换与HTTP重试共用；预算耗尽后不再发起新尝试，
     * 因此在途尝试最多再多花一个响应读取超时。
     */
    private static final class RequestBudget {
        /**
         * 预算总时长（纳秒）
         */
        private static final long BUDGET_NANOS = Duration.ofMillis(TornConstants.HTTP_RETRY_BUDGET_MS).toNanos();
        private final long startNanos = System.nanoTime();
        /**
         * 已完成的Key轮换次数
         */
        private int keyRotationCount;
        /**
         * 已发起的HTTP尝试次数
         */
        private int httpAttemptCount;
        /**
         * 最后一次失败原因
         */
        private Throwable lastCause;

        private boolean exhausted() {
            return System.nanoTime() - startNanos >= BUDGET_NANOS;
        }

        private long elapsedMillis() {
            return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        }

        private void recordKeyRotation() {
            keyRotationCount++;
        }

        private void recordHttpAttempt() {
            httpAttemptCount++;
        }

        private void recordFailure(Throwable cause) {
            this.lastCause = cause;
        }

        private int keyRotationCount() {
            return keyRotationCount;
        }

        private int httpAttemptCount() {
            return httpAttemptCount;
        }

        private String lastCauseName() {
            return lastCause == null ? "NONE" : lastCause.getClass().getSimpleName();
        }

        /**
         * 重试前退避；退避后仍超预算则返回false，调用方必须放弃本次逻辑请求。
         *
         * @return true表示仍有预算可继续重试
         */
        private boolean pause() {
            if (exhausted()) {
                return false;
            }
            try {
                Thread.sleep(TornConstants.HTTP_RETRY_BACKOFF_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            return !exhausted();
        }
    }

    private record ApiResponseResult<T>(
            T result,
            boolean shouldRetry) {
        private static <T> ApiResponseResult<T> success(T result) {
            return new ApiResponseResult<>(result, result == null);
        }

        private static <T> ApiResponseResult<T> stop() {
            return new ApiResponseResult<>(null, false);
        }

        private static <T> ApiResponseResult<T> retry() {
            return new ApiResponseResult<>(null, true);
        }
    }
}
