package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 股票回放只读实现契约测试。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
@DisplayName("股票回放只读契约测试")
class StockReplayReadOnlyContractTest {

    private static final Path REPLAY_ROOT = Path.of(
            "src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/replay");

    @Test
    @DisplayName("回放实现_不调用正式写入编排和系统当前时间")
    void replaySources_doNotCallProductionWritesOrCurrentTime() throws Exception {
        String source = Files.walk(REPLAY_ROOT)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .map(this::read)
                .reduce("", String::concat);

        assertFalse(source.contains("StockRoundTransactionService"));
        assertFalse(source.contains("StockNoticeSendService"));
        assertFalse(source.contains("LocalDateTime.now()"));
        assertFalse(source.contains("updateBatchById"));
        assertFalse(source.contains("insert("));
        assertTrue(source.contains("StockReplayRequest"));
        assertTrue(source.contains("StockReplayBoundary"));
    }

    private String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("读取回放源码失败: " + path, exception);
        }
    }
}
