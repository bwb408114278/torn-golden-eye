package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 股票回放服务只读入口测试。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
@DisplayName("股票回放服务测试")
class StockReplayServiceTest {

    @TempDir
    Path outputDirectory;

    @Test
    @DisplayName("显式输入和时间_生成隔离回放四类产物")
    void run_explicitRequest_generatesIsolatedArtifacts() throws Exception {
        StockReplayRequest request = new StockReplayRequest("VIP_FORMAL",
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 2, 0, 0),
                "BAR_V1", "FEATURE_V1", "BUY_V1", "SELL_V1", "ALLOC_V1", "MSG_V1",
                outputDirectory, EnumSet.of(StockReplayTrackEnum.FORMAL_5_SLOT));

        StockReplayResult result = new StockReplayService(new StockReplayArtifactWriter()).run(request);

        assertEquals("FAILED", result.status());
        assertTrue(Files.exists(outputDirectory.resolve(result.runId() + "-summary.json")));
    }

    @Test
    @DisplayName("仅影子轨道_不读取正式组合状态")
    void run_shadowOnlyRequest_generatesArtifactsWithoutFormalState() throws Exception {
        StockReplayRequest request = new StockReplayRequest("VIP_SHADOW",
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 2, 0, 0),
                "BAR_V1", "FEATURE_V1", "BUY_V1", "SELL_V1", "ALLOC_V1", "MSG_V1",
                outputDirectory, EnumSet.of(StockReplayTrackEnum.UNLIMITED_SHADOW));

        StockReplayResult result = new StockReplayService(new StockReplayArtifactWriter()).run(request);

        assertEquals("COMPLETED", result.status());
        assertTrue(Files.exists(outputDirectory.resolve(result.runId() + "-summary.json")));
    }
}
