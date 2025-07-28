package pn.torn.goldeneye.configuration;

import jakarta.annotation.Resource;
import org.apache.commons.collections4.MapUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.base.torn.TornReqParam;
import pn.torn.goldeneye.base.torn.TornReqParamV2;
import pn.torn.goldeneye.configuration.property.TornApiProperty;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.utils.JsonUtils;

import java.util.Random;

/**
 * Torn Api 类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.22
 */
class TornApiImpl implements TornApi {
    @Resource
    private TornApiProperty apiProperty;
    private static final Random RANDOM = new Random();

    /**
     * Web请求
     */
    private final RestClient restClient;
    /**
     * Web请求, api v2版本
     */
    private final RestClient restClientV2;

    public TornApiImpl() {
        this.restClient = RestClient.builder()
                .baseUrl(TornConstants.BASE_URL)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();

        this.restClientV2 = RestClient.builder()
                .baseUrl(TornConstants.BASE_URL_V2)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }

    @Override
    public <T> T sendRequest(String uri, TornReqParam param, Class<T> responseType) {
        String uriWithParam = uri + "/" +
                (param.getId() == null ? "" : param.getId()) +
                "?selections=" + param.getSection() +
                "&key=" + getEnableKey();

        ResponseEntity<String> entity = this.restClient
                .method(HttpMethod.GET)
                .uri(uriWithParam)
                .retrieve()
                .toEntity(String.class);

        return handleResponse(entity, responseType);
    }

    @Override
    public <T> T sendRequest(TornReqParamV2 param, Class<T> responseType) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(param.uri());
        MultiValueMap<String, String> paramMap = param.buildReqParam();
        if (!MapUtils.isEmpty(paramMap)) {
            uriBuilder.queryParams(paramMap);
        }

        String finalUri = uriBuilder.encode().build().toUriString();
        ResponseEntity<String> entity = this.restClientV2
                .method(HttpMethod.GET)
                .uri(finalUri)
                .header("Authorization", "ApiKey " + getEnableKey())
                .retrieve()
                .toEntity(String.class);

        return handleResponse(entity, responseType);
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
                return null;
            }

            return JsonUtils.jsonToObj(entity.getBody(), responseType);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取可用的Api Key
     *
     * @return Api Key
     */
    private String getEnableKey() {
        return apiProperty.getKey().get(RANDOM.nextInt(apiProperty.getKey().size()));
    }
}