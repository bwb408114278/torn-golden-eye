package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 股票持久化契约测试，锁定版本过滤、事务行锁和数据库自增初始化语义。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.17
 */
@DisplayName("股票持久化契约测试")
class StockPersistenceContractTest {

    private static final Path FEATURE_MAPPER_PATH = Path.of(
            "src/main/resources/mapper/torn/stocks/portfolio/TornStockStrategyFeature15mMapper.xml");
    private static final Path BATCH_MAPPER_PATH = Path.of(
            "src/main/resources/mapper/torn/stocks/portfolio/TornStockVirtualBatchMapper.xml");
    private static final Path ROUND_SERVICE_PATH = Path.of(
            "src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/StockRoundTransactionService.java");
    private static final Path PORTFOLIO_CHANGELOG_PATH = Path.of(
            "src/main/resources/db/changelog/1.0.1-2.0.0/1.2.0/stocks-portfolio.yaml");

    @Test
    @DisplayName("最新特征查询_必须限制特征版本")
    void latestFeatureQuery_filtersFeatureVersion() throws Exception {
        String source = Files.readString(FEATURE_MAPPER_PATH, StandardCharsets.UTF_8);
        String mapperSource = Files.readString(Path.of(
                        "src/main/java/pn/torn/goldeneye/repository/mapper/torn/stocks/portfolio/TornStockStrategyFeature15mMapper.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("AND f.feature_version = #{featureVersion}"),
                "最新特征窗口查询必须限制featureVersion");
        assertTrue(mapperSource.contains("@Param(\"featureVersion\")"),
                "Mapper方法必须显式接收featureVersion");
    }

    @Test
    @DisplayName("活跃批次查询_事务处理入口必须使用行锁")
    void activeBatchQuery_providesTransactionalRowLock() throws Exception {
        String source = Files.readString(BATCH_MAPPER_PATH, StandardCharsets.UTF_8);

        assertTrue(source.contains("id=\"selectActiveFormalBatchesForUpdate\""),
                "必须提供正式批次事务锁查询");
        assertTrue(source.contains("id=\"selectActiveShadowBatchesForUpdate\""),
                "必须提供影子批次事务锁查询");
        assertTrue(countOccurrences(source, "FOR UPDATE") >= 2,
                "正式和影子批次锁查询都必须包含FOR UPDATE");
    }

    @Test
    @DisplayName("轮次事务_必须使用事务内锁定的活跃批次")
    void roundTransaction_usesLockedActiveBatches() throws Exception {
        String source = Files.readString(ROUND_SERVICE_PATH, StandardCharsets.UTF_8);

        assertTrue(source.contains("selectActiveFormalBatchesForUpdate()"),
                "轮次事务必须在事务内重新锁定正式活跃批次");
        assertTrue(source.contains("selectActiveShadowBatchesForUpdate()"),
                "轮次事务必须在事务内重新锁定影子活跃批次");
    }

    @Test
    @DisplayName("槽位初始化_不得显式写入主键")
    void portfolioSlotInitialization_usesDatabaseGeneratedId() throws Exception {
        String source = Files.readString(PORTFOLIO_CHANGELOG_PATH, StandardCharsets.UTF_8);

        assertTrue(source.contains("INSERT INTO torn_stock_portfolio_slot (portfolio_code, slot_no"),
                "槽位初始化必须让数据库生成主键");
        assertFalse(source.contains("INSERT INTO torn_stock_portfolio_slot (id, portfolio_code"),
                "槽位初始化不得显式写入主键");
    }

    private int countOccurrences(String source, String target) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(target, offset)) >= 0) {
            count++;
            offset += target.length();
        }
        return count;
    }
}
