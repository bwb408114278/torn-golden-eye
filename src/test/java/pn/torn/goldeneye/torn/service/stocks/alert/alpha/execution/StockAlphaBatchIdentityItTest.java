package pn.torn.goldeneye.torn.service.stocks.alert.alpha.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.*;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchEntryFields;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockRuleVersion;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockVirtualBatchAssembler;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * α批次业务身份真实PostgreSQL集成测试。
 * <p>
 * 验证"公共成交组装只补成交事实,不得覆盖已冻结的α规则身份": 真实插入α ENTRY_PENDING批次,
 * 经真实 {@link StockVirtualBatchAssembler} 成交组装后用真实 {@link TornStockVirtualBatchDAO}
 * 读回,组合、主策略与四个规则版本必须保持;对照组旧版批次仍写入旧版默认值。
 * 使用隔离股票ID与远端未来时间,{@code @Transactional}+{@code @Rollback}保证不残留数据。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.09
 */
@Tag("shared-db")
@SpringBootTest
@Transactional
@Rollback
@DisplayName("α批次业务身份真实PostgreSQL集成测试")
class StockAlphaBatchIdentityItTest {
    /**
     * 隔离的α批次股票ID(远离生产股票1..35与其他测试命名空间)。
     */
    private static final Integer ALPHA_STOCKS_ID = 2099701;
    /**
     * 隔离的旧版批次股票ID。
     */
    private static final Integer LEGACY_STOCKS_ID = 2099702;
    /**
     * 隔离批次编号。
     */
    private static final String ALPHA_BATCH_NO = "IT-ALPHA-IDENTITY-20990905-0";
    /**
     * 隔离旧版批次编号。
     */
    private static final String LEGACY_BATCH_NO = "IT-LEGACY-IDENTITY-20990905-0";
    /**
     * 决策桶起点。
     */
    private static final LocalDateTime DECISION_BAR = LocalDateTime.of(2099, 9, 5, 9, 45);
    /**
     * 执行桶(成交)起点。
     */
    private static final LocalDateTime ENTRY_BAR = LocalDateTime.of(2099, 9, 5, 10, 0);

    @Autowired
    private TornStockVirtualBatchDAO batchDAO;

    @Test
    @DisplayName("真实PG_Alpha批次成交后组合主策略与四个α规则版本全部保持")
    void applyFilledEntryFields_alphaBatch_keepsFrozenIdentity() {
        TornStockVirtualBatchDO alpha = entryPendingAlphaBatch();
        assertEquals(1, batchDAO.insertIgnoreConflict(alpha));
        TornStockVirtualBatchDO persisted = batchDAO.selectByBatchNoForUpdate(ALPHA_BATCH_NO);
        assertNotNull(persisted, "α批次必须真实落库并可按批次号读回");

        StockVirtualBatchAssembler.applyFilledEntryFields(persisted, entryFields());
        assertAlphaIdentity(persisted, "成交组装后");
        assertEquals(StockBatchStatusEnum.OPEN.getCode(), persisted.getBatchStatus(), "成交后批次状态必须为OPEN");

        batchDAO.updateById(persisted);
        TornStockVirtualBatchDO readBack = batchDAO.selectByBatchNoForUpdate(ALPHA_BATCH_NO);
        assertNotNull(readBack, "成交后必须可按批次号真实读回");
        assertAlphaIdentity(readBack, "数据库读回");
        assertEquals(StockBatchStatusEnum.OPEN.getCode(), readBack.getBatchStatus());
        assertEquals(1000L, readBack.getQuantity());
        assertNotNull(readBack.getFollowUntil(), "成交必须冻结跟随截止时间");
        assertNotNull(readBack.getFollowMaxPrice(), "成交必须冻结最高建议跟随价");
    }

    @Test
    @DisplayName("真实PG_旧版批次成交后仍写入旧版默认规则版本")
    void applyFilledEntryFields_legacyBatch_keepsLegacyDefaults() {
        TornStockVirtualBatchDO legacy = entryPendingLegacyBatch();
        assertEquals(1, batchDAO.insertIgnoreConflict(legacy));
        TornStockVirtualBatchDO persisted = batchDAO.selectByBatchNoForUpdate(LEGACY_BATCH_NO);
        assertNotNull(persisted, "旧版批次必须真实落库");

        StockVirtualBatchAssembler.applyFilledEntryFields(persisted, entryFields());
        batchDAO.updateById(persisted);

        TornStockVirtualBatchDO readBack = batchDAO.selectByBatchNoForUpdate(LEGACY_BATCH_NO);
        assertNotNull(readBack);
        assertEquals(StockLedgerTypeEnum.FORMAL.getCode(), readBack.getLedgerType());
        assertEquals(StockPortfolioService.PORTFOLIO_CODE, readBack.getPortfolioCode(), "旧版组合编码不得变化");
        assertEquals(StockRuleVersion.BUY, readBack.getBuyRuleVersion(), "旧版批次仍写旧版买入规则版本");
        assertEquals(StockRuleVersion.SELL, readBack.getSellRuleVersion(), "旧版批次仍写旧版卖出规则版本");
        assertEquals(StockRuleVersion.ALLOCATION, readBack.getAllocationRuleVersion(), "旧版批次仍写旧版分配规则版本");
        assertEquals(StockRuleVersion.MESSAGE, readBack.getMessageRuleVersion(), "旧版批次仍写旧版消息规则版本");
    }

    /**
     * 断言α批次身份字段在真实读回后仍为冻结的α身份。
     *
     * @param batch      批次
     * @param stageAlias 断言阶段描述
     */
    private void assertAlphaIdentity(TornStockVirtualBatchDO batch, String stageAlias) {
        assertEquals(StockLedgerTypeEnum.FORMAL.getCode(), batch.getLedgerType(), stageAlias + "账本类型必须保持FORMAL");
        assertEquals(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE, batch.getPortfolioCode(),
                stageAlias + "α组合编码不得被覆盖为旧版默认值");
        assertEquals(StockAlphaRuleDefinition.PRIMARY_STRATEGY, batch.getPrimaryStrategy(),
                stageAlias + "α主策略不得被覆盖为旧版默认值");
        assertEquals(StockAlphaRuleDefinition.RULE_VERSION, batch.getBuyRuleVersion(), stageAlias + "α买入规则版本必须保持");
        assertEquals(StockAlphaRuleDefinition.SELL_RULE_VERSION, batch.getSellRuleVersion(), stageAlias + "α卖出规则版本必须保持");
        assertEquals(StockAlphaRuleDefinition.ALLOCATION_RULE_VERSION, batch.getAllocationRuleVersion(),
                stageAlias + "α分配规则版本必须保持");
        assertEquals(StockAlphaRuleDefinition.MESSAGE_RULE_VERSION, batch.getMessageRuleVersion(),
                stageAlias + "α消息规则版本必须保持");
        assertEquals(77L, batch.getAlphaDecisionId(), stageAlias + "α来源决策关联必须保持");
    }

    /**
     * 构造已冻结α身份的ENTRY_PENDING批次。
     *
     * @return α待入场批次
     */
    private TornStockVirtualBatchDO entryPendingAlphaBatch() {
        TornStockVirtualBatchDO batch = baseEntryPendingBatch(ALPHA_BATCH_NO, ALPHA_STOCKS_ID);
        StockAlphaBatchIdentity.applyAlphaIdentity(batch);
        batch.setAlphaDecisionId(77L);
        batch.setStyleRuleVersion(StockAlphaRuleDefinition.STYLE_RULE_VERSION);
        batch.setRiskRuleVersion(StockAlphaRuleDefinition.RISK_RULE_VERSION);
        return batch;
    }

    /**
     * 构造旧版正式组合的ENTRY_PENDING批次。
     *
     * @return 旧版待入场批次
     */
    private TornStockVirtualBatchDO entryPendingLegacyBatch() {
        TornStockVirtualBatchDO batch = baseEntryPendingBatch(LEGACY_BATCH_NO, LEGACY_STOCKS_ID);
        batch.setPortfolioCode(StockPortfolioService.PORTFOLIO_CODE);
        batch.setPrimaryStrategy("RANGE_LOWER_BUY");
        batch.setBuyRuleVersion(StockRuleVersion.BUY);
        batch.setSellRuleVersion(StockRuleVersion.SELL);
        batch.setAllocationRuleVersion(StockRuleVersion.ALLOCATION);
        batch.setMessageRuleVersion(StockRuleVersion.MESSAGE);
        batch.setStyleRuleVersion(StockRuleVersion.STYLE);
        batch.setRiskRuleVersion(StockRuleVersion.RISK);
        batch.setStylePrior(StockStrategyFitEnum.RANGING.getCode());
        batch.setStyleMaturity(StockMaturityEnum.M3_SEASONED.getCode());
        batch.setRiskLevel(StockRiskLevelEnum.MEDIUM.getCode());
        return batch;
    }

    /**
     * 构造满足批次表非空约束的ENTRY_PENDING基础批次。
     *
     * @param batchNo  批次编号
     * @param stocksId 股票ID
     * @return 待入场批次
     */
    private TornStockVirtualBatchDO baseEntryPendingBatch(String batchNo, Integer stocksId) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setBatchNo(batchNo);
        batch.setLedgerType(StockLedgerTypeEnum.FORMAL.getCode());
        batch.setStocksId(stocksId);
        batch.setStocksShortname("ITSTK");
        batch.setMatchedStrategies("[\"ALPHA\"]");
        batch.setQualityScore(BigDecimal.ZERO);
        batch.setBatchStatus(StockBatchStatusEnum.ENTRY_PENDING.getCode());
        batch.setSignalTime(DECISION_BAR);
        batch.setSignalReferencePrice(new BigDecimal("100.00"));
        batch.setExpectedEntryBarTime(ENTRY_BAR);
        batch.setEntryStaleAt(ENTRY_BAR.plusMinutes(20));
        batch.setStylePrior(StockStrategyFitEnum.ALPHA_NOT_EVALUATED.getCode());
        batch.setStyleMaturity(StockMaturityEnum.ALPHA_NOT_EVALUATED.getCode());
        batch.setRiskLevel(StockRiskLevelEnum.ALPHA_NOT_EVALUATED.getCode());
        batch.setStyleEffectiveMonth(LocalDate.of(2099, 9, 1));
        batch.setResetObserved(false);
        return batch;
    }

    /**
     * 构造一次性成交入场字段。
     *
     * @return 成交入场字段
     */
    private TornStockVirtualBatchEntryFields entryFields() {
        TornStockVirtualBatchEntryFields fields = new TornStockVirtualBatchEntryFields();
        fields.setEntryReferencePrice(new BigDecimal("100.50"));
        fields.setEntryTime(ENTRY_BAR);
        fields.setQuantity(1000L);
        fields.setInvestedCash(new BigDecimal("100500.00"));
        fields.setRemainingCash(new BigDecimal("1999899500.00"));
        return fields;
    }
}
