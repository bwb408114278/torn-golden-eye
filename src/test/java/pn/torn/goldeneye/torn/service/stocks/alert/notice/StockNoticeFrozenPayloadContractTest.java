package pn.torn.goldeneye.torn.service.stocks.alert.notice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 股票通知最终载荷契约测试，锁定发送器只消费冻结文本并在发送前完成载荷最终化。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.27
 */
@DisplayName("股票通知最终载荷契约测试")
class StockNoticeFrozenPayloadContractTest {

    private static final Path SEND_SERVICE_PATH = Path.of(
            "src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/notice/StockNoticeSendService.java");
    private static final Path AUDIT_MAPPER_PATH = Path.of(
            "src/main/resources/mapper/torn/stocks/portfolio/TornStockNoticeAuditMapper.xml");

    @Test
    @DisplayName("通知发送_必须优先消费冻结消息文本")
    void noticeSending_consumesFrozenMessageText() throws Exception {
        String source = Files.readString(SEND_SERVICE_PATH, StandardCharsets.UTF_8);

        assertTrue(source.contains("getFrozenMessageText"), "发送器必须通过冻结文本入口读取消息");
        assertTrue(source.contains("extractFrozenMessageText"), "发送器必须能够恢复中断前已冻结的文本");
        assertTrue(!source.contains("composeAndMergeNotices(validNotices, batchMap)"),
                "发送器不得在发送阶段重新解释批次");
    }

    @Test
    @DisplayName("通知最终化_必须写入发送时间和冻结文本")
    void noticeFinalization_persistsSendTimeAndFrozenText() throws Exception {
        String source = Files.readString(AUDIT_MAPPER_PATH, StandardCharsets.UTF_8);

        assertTrue(source.contains("finalizePayload"), "Mapper必须提供通知最终载荷更新入口");
        assertTrue(source.contains("payload_snapshot"), "最终化必须写入payload_snapshot");
        assertTrue(source.contains("attempted_at"), "最终化必须写入实际发送尝试时间");
        assertTrue(source.contains("send_status = 'PENDING'"), "最终化必须保留PENDING并发保护");
    }
}
