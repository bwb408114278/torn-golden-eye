package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 股票组合数据库主键契约测试,验证bar和feature首次UPSERT依赖数据库默认ID生成。
 *
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
@DisplayName("股票组合数据库主键契约测试")
class StockPortfolioDatabaseIdContractTest {

    private static final String CHANGELOG =
            "db/changelog/1.0.1-2.0.0/1.2.0/stocks-portfolio.yaml";
    private static final String BAR_MAPPER =
            "/mapper/torn/stocks/portfolio/TornStockMarketBar15mMapper.xml";
    private static final String FEATURE_MAPPER =
            "/mapper/torn/stocks/portfolio/TornStockStrategyFeature15mMapper.xml";

    @Test
    @DisplayName("股票bar迁移声明sequence默认值且首次UPSERT不传主键")
    void marketBarSchemaAndUpsert_useDatabaseGeneratedId() throws Exception {
        String changelog = readResource(CHANGELOG);
        String mapper = readResource(BAR_MAPPER);

        assertContainsIgnoreCase(changelog, "torn_stock_market_bar_15m_id_seq");
        assertContainsIgnoreCase(changelog, "defaultValueComputed");
        assertContainsIgnoreCase(changelog, "nextval('torn_stock_market_bar_15m_id_seq'::regclass)");
        String insert = statementText(parseXmlContent(mapper), "upsertBar");
        assertContainsIgnoreCase(insert, "insert into torn_stock_market_bar_15m");
        assertNotContainsIgnoreCase(insert, "(id,");
        assertNotContainsIgnoreCase(insert, ", id,");
    }

    @Test
    @DisplayName("股票特征迁移声明sequence默认值且首次UPSERT不传主键")
    void strategyFeatureSchemaAndUpsert_useDatabaseGeneratedId() throws Exception {
        String changelog = readResource(CHANGELOG);
        String mapper = readResource(FEATURE_MAPPER);

        assertContainsIgnoreCase(changelog, "torn_stock_strategy_feature_15m_id_seq");
        assertContainsIgnoreCase(changelog, "nextval('torn_stock_strategy_feature_15m_id_seq'::regclass)");
        String insert = statementText(parseXmlContent(mapper), "upsertFeature");
        assertContainsIgnoreCase(insert, "insert into torn_stock_strategy_feature_15m");
        assertNotContainsIgnoreCase(insert, "(id,");
        assertNotContainsIgnoreCase(insert, ", id,");
    }

    @Test
    @DisplayName("股票特征最新查询_必须按featureVersion过滤历史版本")
    void strategyFeatureLatestQuery_filtersByFeatureVersion() throws Exception {
        String mapper = readResource(FEATURE_MAPPER);
        String query = statementText(parseXmlContent(mapper), "selectLatestByStocksIds");

        assertContainsIgnoreCase(query, "feature_version = #{featureVersion}");
    }

    private Document parseXml(String resource) throws Exception {
        try (InputStream inputStream = getClass().getResourceAsStream(resource)) {
            if (inputStream == null) {
                throw new IllegalStateException("Mapper XML资源不存在: " + resource);
            }
            return parseXml(inputStream);
        }
    }

    private Document parseXmlContent(String content) throws Exception {
        return parseXml(new java.io.ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    private Document parseXml(InputStream inputStream) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) ->
                new org.xml.sax.InputSource(new java.io.StringReader("")));
        return builder.parse(inputStream);
    }

    private String readResource(String resource) throws Exception {
        try (InputStream inputStream = resource.startsWith("/")
                ? getClass().getResourceAsStream(resource)
                : getClass().getClassLoader().getResourceAsStream(resource)) {
            if (inputStream == null) {
                throw new IllegalStateException("测试资源不存在: " + resource);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String statementText(Document document, String statementId) {
        for (String tagName : new String[]{"insert", "select"}) {
            NodeList nodes = document.getElementsByTagName(tagName);
            for (int index = 0; index < nodes.getLength(); index++) {
                Element element = (Element) nodes.item(index);
                if (statementId.equals(element.getAttribute("id"))) {
                    return element.getTextContent().replaceAll("\\s+", " ").trim();
                }
            }
        }
        throw new IllegalStateException("Mapper语句不存在: " + statementId);
    }

    private void assertContainsIgnoreCase(String actual, String expected) {
        assertTrue(actual.toLowerCase().contains(expected.toLowerCase()),
                () -> "契约缺少片段: " + expected);
    }

    private void assertNotContainsIgnoreCase(String actual, String unexpected) {
        assertFalse(actual.toLowerCase().contains(unexpected.toLowerCase()),
                () -> "契约包含不应出现的片段: " + unexpected);
    }
}
