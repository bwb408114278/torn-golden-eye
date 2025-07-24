package pn.torn.goldeneye.configuration;

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
import pn.torn.goldeneye.constants.torn.TornConstants;

/**
 * Torn Api 类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.22
 */
class TornApiImpl implements TornApi {
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
    public <T> ResponseEntity<T> sendRequest(String uri, TornReqParam param, Class<T> responseType) {
        String uriWithParam = uri + "/" +
                (param.getId() == null ? "" : param.getId()) +
                "?selections=" + param.getSection() +
                "&key=" + getEnableKey();

        return this.restClient
                .method(HttpMethod.GET)
                .uri(uriWithParam)
                .retrieve()
                .toEntity(responseType);
    }

    @Override
    public <T> ResponseEntity<T> sendRequest(String uri, TornReqParamV2 param, Class<T> responseType) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(uri);
        MultiValueMap<String, String> paramMap = param.buildReqParam();
        if (!MapUtils.isEmpty(paramMap)) {
            uriBuilder.queryParams(paramMap);
        }

        String finalUri = uriBuilder.encode().build().toUriString();
        return this.restClientV2
                .method(HttpMethod.GET)
                .uri(finalUri)
                .header("Authorization", "ApiKey " + getEnableKey())
                .retrieve()
                .toEntity(responseType);
    }

    /**
     * 获取可用的Api Key
     *
     * @return Api Key
     */
    private String getEnableKey() {
        return "";
    }
}