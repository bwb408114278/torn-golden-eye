package pn.torn.goldeneye.torn.service.stocks.alert.notice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 股票通知载荷契约测试，锁定发送端消费冻结文本而不是重新解释批次。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.17
 */
@DisplayName("股票通知载荷契约测试")
class StockNoticePayloadContractTest {

    private static final Path SEND_SERVICE_PATH = Path.of(
            "src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/notice/StockNoticeSendService.java");
    private static final Path AUDIT_MAPPER_PATH = Path.of(
            "src/main/resources/mapper/torn/stocks/portfolio/TornStockNoticeAuditMapper.xml");

    @Test
    @DisplayName("通知发送_必须读取审计冻结文本")
    void noticeSending_consumesFrozenPayloadText() throws Exception {
        String source = Files.readString(SEND_SERVICE_PATH, StandardCharsets.UTF_8);

        assertTrue(source.contains("payloadSnapshot"), "发送服务必须读取冻结载荷");
        assertTrue(source.contains("messageText"), "冻结载荷必须包含最终消息文本");
        assertFalse(source.contains("composeAndMergeNotices(validNotices, batchMap)"), "发送阶段不得重新组合可变批次");
    }

    @Test
    @DisplayName("通知状态更新_必须保留待发送条件和累计尝试次数")
    void noticeStatusUpdate_preservesPendingGuardAndAttemptIncrement() throws Exception {
        String source = Files.readString(AUDIT_MAPPER_PATH, StandardCharsets.UTF_8);

        assertTrue(source.contains("send_status = 'PENDING'"), "状态更新必须保护PENDING状态");
        assertTrue(source.contains("COALESCE(send_attempt_count, 0) + 1"),
                "发送尝试次数必须累计递增");
    }
}
