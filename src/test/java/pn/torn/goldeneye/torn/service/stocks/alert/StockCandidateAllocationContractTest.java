package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 股票候选接纳契约测试，锁定资格、正式分配和失败原因的分离边界。
 *
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
@DisplayName("股票候选接纳契约测试")
class StockCandidateAllocationContractTest {

    private static final Path EVALUATOR_PATH = Path.of(
            "src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/StockBuySignalEvaluator.java");
    private static final Path WRITER_PATH = Path.of(
            "src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/StockShadowRecordWriter.java");

    @Test
    @DisplayName("候选接纳_必须返回实际分配结果而不是仅返回批次列表")
    void candidateAcceptance_returnsAllocationResult() throws Exception {
        String source = Files.readString(EVALUATOR_PATH, StandardCharsets.UTF_8);

        assertTrue(source.contains("StockCandidateAllocationResult acceptCandidates("),
                "候选接纳必须返回包含失败原因的结果对象");
        assertTrue(source.contains("NO_AVAILABLE_SLOT"), "候选接纳必须记录无槽位原因");
        assertTrue(source.contains("INSUFFICIENT_FUNDS"), "候选接纳必须记录资金不足原因");
    }

    @Test
    @DisplayName("候选接纳_事件写入必须消费实际分配结果")
    void candidateAcceptance_eventWriterConsumesAllocationResult() throws Exception {
        String source = Files.readString(WRITER_PATH, StandardCharsets.UTF_8);

        assertTrue(source.contains("allocationResultByStockId"),
                "事件写入器必须接收实际分配结果");
        assertTrue(source.contains("StockCandidateAllocationResultEnum"),
                "事件写入器必须按接纳结果决定研究轨道和原因");
    }
}
