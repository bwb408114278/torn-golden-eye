package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 股票提醒Mapper XML契约测试,验证批量通知状态和日报批次查询的关键SQL边界。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.28
 */
@DisplayName("股票提醒Mapper XML契约测试")
class StockPortfolioMapperXmlContractTest {

    private static final String NOTICE_AUDIT_MAPPER =
            "/mapper/torn/stocks/portfolio/TornStockNoticeAuditMapper.xml";
    private static final String VIRTUAL_BATCH_MAPPER =
            "/mapper/torn/stocks/portfolio/TornStockVirtualBatchMapper.xml";

    @Test
    @DisplayName("通知审计批量更新_SQL包含PENDING保护和尝试次数递增")
    void noticeAuditBatchUpdates_containPendingGuardAndAttemptIncrement() throws Exception {
        Document document = parseXml(NOTICE_AUDIT_MAPPER);
        for (String statementId : new String[]{"markFailedByIds", "markSentByIds", "markSendFailedByIds"}) {
            String sql = statementText(document, statementId);
            assertContainsIgnoreCase(sql, "deleted = 0");
            assertContainsIgnoreCase(sql, "send_status = 'PENDING'");
            assertContainsIgnoreCase(sql, "COALESCE(send_attempt_count, 0) + 1");
            assertContainsIgnoreCase(sql, "id IN");
        }
        assertContainsIgnoreCase(statementText(document, "markFailedByIds"), "error_message");
        assertContainsIgnoreCase(statementText(document, "markSendFailedByIds"), "error_message");
        assertContainsIgnoreCase(statementText(document, "markSentByIds"), "sent_at");
    }

    @Test
    @DisplayName("日报批次查询_SQL区分正式和Shadow动作时间")
    void dailyBatchQueries_separateFormalAndShadowActionTimes() throws Exception {
        Document document = parseXml(VIRTUAL_BATCH_MAPPER);
        String formalSql = statementText(document, "selectFormalActionBatches");
        String shadowSql = statementText(document, "selectShadowActionBatches");

        assertContainsIgnoreCase(formalSql, "ledger_type = 'FORMAL'");
        assertContainsIgnoreCase(formalSql, "entry_time");
        assertContainsIgnoreCase(formalSql, "exit_time");
        assertNotContainsIgnoreCase(formalSql, "signal_time");
        assertContainsIgnoreCase(formalSql, "deleted = 0");
        assertContainsIgnoreCase(formalSql, "ORDER BY id ASC");

        assertContainsIgnoreCase(shadowSql, "ledger_type IN ('UNLIMITED_SHADOW', 'REJECTED_OBSERVATION')");
        assertContainsIgnoreCase(shadowSql, "signal_time");
        assertContainsIgnoreCase(shadowSql, "exit_time");
        assertContainsIgnoreCase(shadowSql, "deleted = 0");
        assertContainsIgnoreCase(shadowSql, "ORDER BY id ASC");
    }

    private Document parseXml(String resourcePath) throws Exception {
        try (InputStream inputStream = getClass().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("Mapper XML资源不存在: " + resourcePath);
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new org.xml.sax.InputSource(
                    new java.io.StringReader("")));
            return builder.parse(inputStream);
        }
    }

    private String statementText(Document document, String statementId) {
        NodeList nodes = document.getElementsByTagName("update");
        for (int index = 0; index < nodes.getLength(); index++) {
            Element element = (Element) nodes.item(index);
            if (statementId.equals(element.getAttribute("id"))) {
                return element.getTextContent().replaceAll("\\s+", " ").trim();
            }
        }
        nodes = document.getElementsByTagName("select");
        for (int index = 0; index < nodes.getLength(); index++) {
            Element element = (Element) nodes.item(index);
            if (statementId.equals(element.getAttribute("id"))) {
                return element.getTextContent().replaceAll("\\s+", " ").trim();
            }
        }
        throw new IllegalStateException("Mapper语句不存在: " + statementId);
    }

    private void assertContainsIgnoreCase(String text, String expected) {
        assertTrue(text.toLowerCase().contains(expected.toLowerCase()),
                () -> "SQL缺少契约片段: " + expected + "; actual=" + text);
    }

    private void assertNotContainsIgnoreCase(String text, String unexpected) {
        assertFalse(text.toLowerCase().contains(unexpected.toLowerCase()), () ->
                "SQL包含不应出现的契约片段: " + unexpected + "; actual=" + text);
    }
}
