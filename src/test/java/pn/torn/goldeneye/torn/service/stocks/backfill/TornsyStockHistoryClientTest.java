package pn.torn.goldeneye.torn.service.stocks.backfill;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import pn.torn.goldeneye.base.exception.BizException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tornsy 股票历史 HTTP 客户端单元测试 - 覆盖 URI 参数、HTTP 非2xx、空 body、解析失败、有限重试与分页
 * <p>
 * 通过注入 mock {@link RestClient} 验证 {@link TornsyStockHistoryClient} 的请求参数组装、
 * 有限退避重试（非2xx/空 body/解析失败均视为失败）以及 {@code from/to/limit} 分页推进逻辑。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.13
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Tornsy 股票历史 HTTP 客户端测试")
class TornsyStockHistoryClientTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private RestClient restClient;

    private TornsyStockHistoryClient client;

    @BeforeEach
    void setUp() {
        client = new TornsyStockHistoryClient(restClient);
    }

    @Test
    @DisplayName("构建URI_包含路径段与interval/from/to/limit参数")
    void buildUri_containsPathAndQueryParams() {
        String uri = client.buildUri("ass", 1000L, 2000L, 500);

        assertTrue(uri.startsWith("/ass?"), "URI应以路径段/ass开头: " + uri);
        assertTrue(uri.contains("interval=m1"), "URI应包含interval=m1: " + uri);
        assertTrue(uri.contains("from=1000"), "URI应包含from=1000: " + uri);
        assertTrue(uri.contains("to=2000"), "URI应包含to=2000: " + uri);
        assertTrue(uri.contains("limit=500"), "URI应包含limit=500: " + uri);
    }

    @Test
    @DisplayName("构建URI_大写简称 -> 路径段转小写")
    void buildUri_uppercaseShortname_lowercasesPath() {
        String uri = client.buildUri("ASS", 1000L, 2000L, 500);

        assertTrue(uri.startsWith("/ass?"), "大写简称应转为小写路径段/ass: " + uri);
    }

    @Test
    @DisplayName("拉取数据_单页少于上限 -> 返回全部行且不分页")
    void fetchMinuteData_singlePage_returnsAllRows() {
        String body = "{\"data\": [[1786520040, \"362.07\", 15795177397]]}";
        when(restClient.get().uri(anyString()).retrieve().toEntity(String.class))
                .thenReturn(ResponseEntity.ok(body));

        List<JsonNode> rows = client.fetchMinuteData("ass", 1786520040L, 1786520100L, 1000);

        assertEquals(1, rows.size());
    }

    @Test
    @DisplayName("拉取数据_满页后按最后epoch推进分页 -> 返回拼接结果")
    void fetchMinuteData_fullPage_advancesPagination() {
        String page1 = "{\"data\": [[1000, \"10.00\", 100], [1060, \"11.00\", 100]]}";
        String page2 = "{\"data\": [[1120, \"12.00\", 100]]}";
        when(restClient.get().uri(anyString()).retrieve().toEntity(String.class))
                .thenReturn(ResponseEntity.ok(page1), ResponseEntity.ok(page2));

        List<JsonNode> rows = client.fetchMinuteData("ass", 1000L, 2000L, 2);

        assertEquals(3, rows.size());
    }

    @Test
    @DisplayName("拉取数据_HTTP非2xx后重试成功 -> 返回结果")
    void fetchPage_non2xxThenSuccess_retriesAndReturns() {
        String body = "{\"data\": [[1786520040, \"362.07\", 15795177397]]}";
        when(restClient.get().uri(anyString()).retrieve().toEntity(String.class))
                .thenReturn(ResponseEntity.status(500).body("error"), ResponseEntity.ok(body));

        List<JsonNode> rows = client.fetchPage("ass", 1786520040L, 1786520100L, 1000);

        assertEquals(1, rows.size());
    }

    @Test
    @DisplayName("拉取数据_持续非2xx重试耗尽 -> 抛出BizException")
    void fetchPage_persistentNon2xx_throwsBizException() {
        when(restClient.get().uri(anyString()).retrieve().toEntity(String.class))
                .thenReturn(ResponseEntity.status(500).body("error"));

        assertThrows(BizException.class, () -> client.fetchPage("ass", 1786520040L, 1786520100L, 1000));
    }

    @Test
    @DisplayName("拉取数据_空响应体重试耗尽 -> 抛出BizException")
    void fetchPage_emptyBody_throwsBizException() {
        when(restClient.get().uri(anyString()).retrieve().toEntity(String.class))
                .thenReturn(ResponseEntity.ok(""));

        assertThrows(BizException.class, () -> client.fetchPage("ass", 1786520040L, 1786520100L, 1000));
    }

    @Test
    @DisplayName("拉取数据_JSON解析失败重试耗尽 -> 抛出BizException")
    void fetchPage_parseFailure_throwsBizException() {
        when(restClient.get().uri(anyString()).retrieve().toEntity(String.class))
                .thenReturn(ResponseEntity.ok("not-a-json"));

        assertThrows(BizException.class, () -> client.fetchPage("ass", 1786520040L, 1786520100L, 1000));
    }
}
