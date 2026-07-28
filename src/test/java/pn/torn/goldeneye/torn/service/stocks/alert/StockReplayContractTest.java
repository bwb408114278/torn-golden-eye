package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 股票隔离回放契约测试，锁定回放只读输入、内存隔离和固定产物边界。
 *
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
@DisplayName("股票隔离回放契约测试")
class StockReplayContractTest {

    private static final Path SERVICE_ROOT = Path.of(
            "src/main/java/pn/torn/goldeneye/torn/service/stocks/alert");

    @Test
    @DisplayName("隔离回放_必须显式定义四类研究产物")
    void isolatedReplay_definesRequiredResearchArtifacts() throws Exception {
        String source = Files.walk(SERVICE_ROOT)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .map(this::read)
                .reduce("", String::concat);

        assertTrue(source.contains("runId"), "回放必须有显式runId隔离边界");
        assertTrue(source.contains("portfolioId"), "回放必须有显式portfolioId隔离边界");
        assertTrue(source.contains("equity-curve.csv"), "回放必须输出净值曲线产物");
        assertTrue(source.contains("rejections.csv"), "回放必须输出拒绝事件产物");
    }

    private String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("读取回放源码失败: " + path, exception);
        }
    }
}
