package pn.torn.goldeneye.torn.service.stocks.backfill;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.utils.JsonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tornsy 股票历史 HTTP 客户端 - 请求 Tornsy m1 分钟点接口并处理有限重试与分页
 * <p>
 * 复用 {@link RestClient}，不使用 Torn API Key；仅消费 m1 分钟点接口，请求地址为
 * {@code GET /{stocksShortname}?interval=m1&from={epochSecond}&to={epochSecond}&limit={pageLimit}}。
 * 连接/响应超时默认 20 秒，单页最多 3 次退避重试；HTTP 非 2xx、空 body、JSON 解析失败
 * 均视为当前股票/时间片失败。日志仅输出股票、范围、页数、状态，不输出完整响应。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.13
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TornsyStockHistoryClient {

    /**
     * Tornsy 接口基础地址
     */
    public static final String BASE_URL = "https://tornsy.com/api";
    /**
     * 分钟点接口的 interval 参数值
     */
    private static final String INTERVAL_M1 = "m1";
    /**
     * 单页最大重试次数
     */
    private static final int MAX_RETRIES = 3;
    /**
     * 退避基数（毫秒）
     */
    private static final long BACKOFF_BASE_MILLIS = 500L;

    private final RestClient restClient;

    /**
     * 拉取指定股票、指定非饱和时间范围内的 m1 分钟数据。
     * <p>
     * 客户端只发起一次请求，不做任何基于响应首/末时间的分页推进。调用方必须保证
     * {@code fromEpochSecond} 到 {@code toEpochSecond} 的窗口长度小于 {@code pageLimit}，
     * 避免满页后无法判断是否漏数；若响应行数达到 {@code pageLimit}，由调用方 fail-closed。
     *
     * @param stocksShortname 股票简称（Tornsy 路径段）
     * @param fromEpochSecond 起始 epoch 秒（含）
     * @param toEpochSecond   结束 epoch 秒（不含）
     * @param pageLimit       单页返回上限
     * @return 该时间片的 m1 原始行数组（可能为空）
     */
    public List<JsonNode> fetchMinuteData(String stocksShortname, long fromEpochSecond,
                                          long toEpochSecond, int pageLimit) {
        return fetchPage(stocksShortname, fromEpochSecond, toEpochSecond, pageLimit);
    }

    /**
     * 拉取单页 m1 数据（有限退避重试）
     *
     * @param stocksShortname 股票简称
     * @param fromEpochSecond 起始 epoch 秒（含）
     * @param toEpochSecond   结束 epoch 秒（不含）
     * @param pageLimit       单页返回上限
     * @return 本页 m1 原始行数组
     * @throws BizException 重试耗尽后抛出
     */
    List<JsonNode> fetchPage(String stocksShortname, long fromEpochSecond, long toEpochSecond, int pageLimit) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                ResponseEntity<String> response = restClient.get()
                        .uri(buildUri(stocksShortname, fromEpochSecond, toEpochSecond, pageLimit))
                        .retrieve()
                        .toEntity(String.class);
                if (response == null || !response.getStatusCode().is2xxSuccessful()) {
                    throw new RestClientException("Tornsy m1 非2xx响应, 股票=" + stocksShortname);
                }
                String body = response.getBody();
                if (body == null || body.isBlank()) {
                    throw new RestClientException("Tornsy m1 空响应体, 股票=" + stocksShortname);
                }
                return extractDataArray(body);
            } catch (Exception e) {
                if (attempt >= MAX_RETRIES) {
                    throw new BizException("Tornsy m1请求失败, 股票=" + stocksShortname, e);
                }
                log.warn("Tornsy m1请求失败, 第{}/{}次重试, 股票={}, from={}, to={}: {}",
                        attempt, MAX_RETRIES, stocksShortname, fromEpochSecond, toEpochSecond, e.getMessage());
                sleepBackoff(attempt);
            }
        }
        throw new IllegalStateException("Tornsy m1请求不可达: " + stocksShortname);
    }

    /**
     * 构建 m1 接口请求 URI
     *
     * @param stocksShortname 股票简称（路径段）
     * @param fromEpochSecond 起始 epoch 秒
     * @param toEpochSecond   结束 epoch 秒
     * @param pageLimit       单页返回上限
     * @return 请求 URI 字符串
     */
    String buildUri(String stocksShortname, long fromEpochSecond, long toEpochSecond, int pageLimit) {
        return UriComponentsBuilder.newInstance()
                .path("/" + stocksShortname.toLowerCase(Locale.ROOT))
                .queryParam("interval", INTERVAL_M1)
                .queryParam("from", fromEpochSecond)
                .queryParam("to", toEpochSecond)
                .queryParam("limit", pageLimit)
                .build()
                .toUriString();
    }

    /**
     * 从响应体提取 m1 data 数组
     *
     * @param body 响应体
     * @return data 数组行列表
     */
    private List<JsonNode> extractDataArray(String body) {
        JsonNode data = JsonUtils.getNode(body, "data");
        if (data == null || !data.isArray()) {
            throw new RestClientException("Tornsy m1 响应缺少有效data数组");
        }
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode row : data) {
            rows.add(row);
        }
        return rows;
    }

    /**
     * 退避休眠
     *
     * @param attempt 当前尝试次数（从 1 开始）
     */
    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(BACKOFF_BASE_MILLIS * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
