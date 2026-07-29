package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 股票日报行情可信度契约测试，锁定行情不足时不得伪造完整权益。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.27
 */
@DisplayName("股票日报行情可信度契约测试")
class StockDailySummaryDataQualityContractTest {

    private static final Path SERVICE_PATH = Path.of(
            "src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/StockDailySummaryService.java");

    @Test
    @DisplayName("日报权益_必须检查可用bar和正价格")
    void dailySummary_equityRequiresUsablePositivePrice() throws Exception {
        String source = Files.readString(SERVICE_PATH, StandardCharsets.UTF_8);

        assertTrue(source.contains("getUsable()"), "日报权益必须校验bar可用性");
        assertTrue(source.contains("lastPrice"), "日报权益必须使用bar的lastPrice");
        assertTrue(source.contains("return null;"), "行情不足时权益必须返回null");
        assertTrue(source.contains("行情数据不足"), "行情不足时摘要必须明确提示数据不足");
    }

    @Test
    @DisplayName("日报陈旧统计_必须包含数据陈旧退出")
    void dailySummary_staleCountIncludesStaleExit() throws Exception {
        String source = Files.readString(SERVICE_PATH, StandardCharsets.UTF_8);

        assertTrue(source.contains("DATA_STALE_EXIT"), "陈旧统计必须包含DATA_STALE_EXIT");
    }
}
