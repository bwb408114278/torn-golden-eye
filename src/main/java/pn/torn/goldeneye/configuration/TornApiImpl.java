package pn.torn.goldeneye.configuration;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.base.torn.TornReqParam;
import pn.torn.goldeneye.base.torn.TornReqParamV2;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.dao.setting.TornApiKeyDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.utils.JsonUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;

/**
 * Torn Api 类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.22
 */
@Slf4j
class TornApiImpl implements TornApi {
    private final DynamicTaskService taskService;
    private final TornApiKeyDAO keyDao;
    private static final Queue<TornApiKeyDO> KEY_QUEUE =
            new PriorityQueue<>(Comparator.comparingInt(TornApiKeyDO::getUseCount));
    private static final Queue<TornApiKeyDO> FACTION_KEY_QUEUE =
            new PriorityQueue<>(Comparator.comparingInt(TornApiKeyDO::getUseCount));

    /**
     * Web请求
     */
    private final RestClient restClient;
    /**
     * Web请求, api v2版本
     */
    private final RestClient restClientV2;

    public TornApiImpl(TornApiKeyDAO keyDao, DynamicTaskService taskService) {
        this.keyDao = keyDao;
        this.taskService = taskService;
        this.restClient = RestClient.builder()
                .baseUrl(TornConstants.BASE_URL)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();

        this.restClientV2 = RestClient.builder()
                .baseUrl(TornConstants.BASE_URL_V2)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();

        refreshKeyData();
    }

    @Override
    public <T> T sendRequest(String uri, TornReqParam param, Class<T> responseType) {
        try {
            String uriWithParam = uri + "/" +
                    (param.getId() == null ? "" : param.getId()) +
                    "?selections=" + param.getSection() +
                    "&key=" + getEnableKey(param.needFactionAccess());

            ResponseEntity<String> entity = this.restClient
                    .method(HttpMethod.GET)
                    .uri(uriWithParam)
                    .retrieve()
                    .toEntity(String.class);

            return handleResponse(entity, responseType);
        } catch (Exception e) {
            log.error("请求Torn Api出错", e);
            return null;
        }
    }

    @Override
    public <T> T sendRequest(TornReqParamV2 param, Class<T> responseType) {
        return sendRequest(param, getEnableKey(param.needFactionAccess()), responseType);
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
            ResponseEntity<String> entity = this.restClientV2
                    .method(HttpMethod.GET)
                    .uri(finalUri)
                    .header("Authorization", "ApiKey " + apiKey.getApiKey())
                    .retrieve()
                    .toEntity(String.class);

            return handleResponse(entity, responseType);
        } catch (Exception e) {
            log.error("请求Torn Api V2出错", e);
            return null;
        } finally {
            updateKeyUseCount(apiKey);
        }
    }

    @Override
    public List<TornApiKeyDO> getEnableKeyList() {
        return KEY_QUEUE.stream().toList();
    }

    /**
     * 处理响应体
     */
    private <T> T handleResponse(ResponseEntity<String> entity, Class<T> responseType) {
        try {
            if (entity.getBody().isEmpty()) {
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

    /**
     * 刷新API KEY数据
     */
    private void refreshKeyData() {
        KEY_QUEUE.clear();
        FACTION_KEY_QUEUE.clear();

        LocalDate queryDate = LocalTime.now().isAfter(LocalTime.of(8, 0)) ?
                LocalDate.now() : LocalDate.now().plusDays(-1);

        List<TornApiKeyDO> keyList = keyDao.queryListByDate(queryDate);
        if (CollectionUtils.isEmpty(keyList)) {
            keyList = keyDao.lambdaQuery().eq(TornApiKeyDO::getUseDate, queryDate.plusDays(-1)).list();
            List<TornApiKeyDO> newList = keyList.stream().map(TornApiKeyDO::copyNewData).toList();
            keyDao.saveBatch(newList);
            keyList = newList;
        }

        KEY_QUEUE.addAll(keyList);
        FACTION_KEY_QUEUE.addAll(keyList.stream().filter(TornApiKeyDO::getHasFactionAccess).toList());
        taskService.updateTask("refresh-api", this::refreshKeyData,
                LocalDate.now().atTime(8, 1, 0).plusDays(1));
    }

    /**
     * 获取可用的Api Key
     *
     * @param needFactionAccess 是否需要帮派权限
     */
    private TornApiKeyDO getEnableKey(boolean needFactionAccess) {
        return needFactionAccess ? FACTION_KEY_QUEUE.poll() : KEY_QUEUE.poll();
    }

    /**
     * 获取可用的Api Key
     */
    private void updateKeyUseCount(TornApiKeyDO key) {
        key.setUseCount(key.getUseCount() + 1);
        KEY_QUEUE.offer(key);

        FACTION_KEY_QUEUE.stream()
                .filter(k -> k.getId().equals(key.getId()))
                .findAny()
                .ifPresent(facKey -> FACTION_KEY_QUEUE.offer(key));

        keyDao.lambdaUpdate()
                .set(TornApiKeyDO::getUseCount, key.getUseCount())
                .eq(TornApiKeyDO::getId, key.getId())
                .update();
    }
}