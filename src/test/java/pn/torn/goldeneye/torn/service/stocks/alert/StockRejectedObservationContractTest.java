package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 股票拒绝观察契约测试，锁定拒绝观察不进入正式成交生命周期。
 *
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
@DisplayName("股票拒绝观察契约测试")
class StockRejectedObservationContractTest {

    private static final Path SOURCE_PATH = Path.of(
            "src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/StockShadowService.java");

    @Test
    @DisplayName("拒绝观察_必须保留理论结果解析入口")
    void rejectedObservation_exposesResultResolutionBoundary() throws Exception {
        String source = Files.readString(SOURCE_PATH, StandardCharsets.UTF_8);

        assertTrue(source.contains("REJECTED_OBSERVATION"), "必须保留拒绝观察账本类型");
        assertTrue(source.contains("laterMfe"), "必须存在laterMfe结果回写边界");
        assertTrue(source.contains("laterMae"), "必须存在laterMae结果回写边界");
        assertTrue(source.contains("resolvedAt"), "必须存在resolvedAt结果回写边界");
    }
}
