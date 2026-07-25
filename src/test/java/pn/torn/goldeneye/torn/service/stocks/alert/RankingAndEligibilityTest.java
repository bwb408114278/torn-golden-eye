package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBuyStrategyEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockEligibilityResultEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockStrategyFitEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalStateDO;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.BuyContext;
import pn.torn.goldeneye.torn.service.stocks.alert.policy.CandidateInfo;
import pn.torn.goldeneye.torn.service.stocks.alert.policy.StockCandidateRankingPolicy;
import pn.torn.goldeneye.torn.service.stocks.alert.StockEligibilityService.EligibilityResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 候选排序策略与买入资格判断服务的测试，覆盖技术方案16.3中排序规则与8项资格门禁的全部场景。
 * 排序测试验证质量分降序、股票ID升序及空列表处理；资格测试按固定检查顺序逐项验证短路返回。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@DisplayName("候选排序与资格判断测试")
class RankingAndEligibilityTest {

    // ==================== 候选排序策略测试 ====================

    @DisplayName("候选排序策略测试")
    @Nested
    class StockCandidateRankingPolicyTest {

        private StockCandidateRankingPolicy policy;

        @BeforeEach
        void setUp() {
            policy = new StockCandidateRankingPolicy();
        }

        @Test
        @DisplayName("rank_质量分降序排序_正确")
        void rank_qualityScoreDescendingOrder_correct() {
            CandidateInfo low = buildCandidate(1001, "LOW", new BigDecimal("80"));
            CandidateInfo high = buildCandidate(1002, "HIGH", new BigDecimal("126"));
            CandidateInfo mid = buildCandidate(1003, "MID", new BigDecimal("100"));

            List<CandidateInfo> result = policy.rank(List.of(low, high, mid));

            assertEquals(3, result.size());
            // 降序：HIGH(126) -> MID(100) -> LOW(80)
            assertEquals(1002, result.get(0).stocksId());
            assertEquals(1003, result.get(1).stocksId());
            assertEquals(1001, result.get(2).stocksId());
        }

        @Test
        @DisplayName("rank_质量分相同_按股票ID升序")
        void rank_qualityScoreEqual_orderByStocksIdAscending() {
            CandidateInfo c3 = buildCandidate(1003, "C3", new BigDecimal("100"));
            CandidateInfo c1 = buildCandidate(1001, "C1", new BigDecimal("100"));
            CandidateInfo c2 = buildCandidate(1002, "C2", new BigDecimal("100"));

            List<CandidateInfo> result = policy.rank(List.of(c3, c1, c2));

            assertEquals(3, result.size());
            // 质量分相同时ID升序：1001 -> 1002 -> 1003
            assertEquals(1001, result.get(0).stocksId());
            assertEquals(1002, result.get(1).stocksId());
            assertEquals(1003, result.get(2).stocksId());
        }

        @Test
        @DisplayName("rank_空列表_返回空列表")
        void rank_emptyList_returnsEmptyList() {
            List<CandidateInfo> result = policy.rank(List.of());
            assertTrue(result.isEmpty());

            List<CandidateInfo> nullResult = policy.rank(null);
            assertTrue(nullResult.isEmpty());
        }
    }

    // ==================== 资格判断服务测试 ====================

    @DisplayName("买入资格判断服务测试")
    @Nested
    class StockEligibilityServiceTest {

        private StockEligibilityService service;

        @BeforeEach
        void setUp() {
            service = new StockEligibilityService();
        }

        @Test
        @DisplayName("checkEligibility_风格缺失_REJECTED")
        void checkEligibility_styleMissing_rejected() {
            BuyContext context = buildEligibilityContext(
                    null,
                    StockMaturityEnum.M3_SEASONED,
                    StockRiskLevelEnum.NONE,
                    Boolean.TRUE);

            EligibilityResult result = service.checkEligibility(context, null, null, false);

            assertEquals(StockEligibilityResultEnum.REJECTED, result.result());
            assertTrue(result.reasons().contains("STYLE_MISSING"));
        }

        @Test
        @DisplayName("checkEligibility_风格不适配_不被本服务拒绝由调用方校验")
        void checkEligibility_styleNotFit_notRejectedByThisService() {
            // 实际实现中风格不适配检查由调用方通过策略的isApplicableStyle完成，
            // 本服务仅判断style==null为缺失。传入非null但策略不适配的风格时应继续后续检查，
            // 此处验证STRONG风格（三策略均不适配）在其他条件正常时仍返回ALLOWED，
            // 证明本服务不执行风格适配性校验。
            BuyContext context = buildEligibilityContext(
                    StockStrategyFitEnum.STRONG,
                    StockMaturityEnum.M3_SEASONED,
                    StockRiskLevelEnum.NONE,
                    Boolean.TRUE);

            EligibilityResult result = service.checkEligibility(context, null, null, false);

            assertEquals(StockEligibilityResultEnum.ALLOWED, result.result());
        }

        @Test
        @DisplayName("checkEligibility_成熟度M0_REJECTED")
        void checkEligibility_maturityM0_rejected() {
            BuyContext context = buildEligibilityContext(
                    StockStrategyFitEnum.RANGING,
                    StockMaturityEnum.M0_UNMATURE,
                    StockRiskLevelEnum.NONE,
                    Boolean.TRUE);

            EligibilityResult result = service.checkEligibility(context, null, null, false);

            assertEquals(StockEligibilityResultEnum.REJECTED, result.result());
            assertTrue(result.reasons().contains("MATURITY_INSUFFICIENT"));
        }

        @Test
        @DisplayName("checkEligibility_风险HIGH_OBSERVED")
        void checkEligibility_riskHigh_observed() {
            BuyContext context = buildEligibilityContext(
                    StockStrategyFitEnum.RANGING,
                    StockMaturityEnum.M3_SEASONED,
                    StockRiskLevelEnum.HIGH,
                    Boolean.TRUE);

            EligibilityResult result = service.checkEligibility(context, null, null, false);

            assertEquals(StockEligibilityResultEnum.OBSERVED, result.result());
            assertTrue(result.reasons().contains("HIGH_RISK_OBSERVED"));
        }

        @Test
        @DisplayName("checkEligibility_冷却中_REJECTED")
        void checkEligibility_inCooldown_rejected() {
            BuyContext context = buildEligibilityContext(
                    StockStrategyFitEnum.RANGING,
                    StockMaturityEnum.M3_SEASONED,
                    StockRiskLevelEnum.NONE,
                    Boolean.TRUE);

            TornStockSignalStateDO signalState = new TornStockSignalStateDO();
            signalState.setCooldownUntil(LocalDateTime.now().plusHours(2));

            EligibilityResult result = service.checkEligibility(context, signalState, null, false);

            assertEquals(StockEligibilityResultEnum.REJECTED, result.result());
            assertTrue(result.reasons().contains("COOLDOWN_ACTIVE"));
        }

        @Test
        @DisplayName("checkEligibility_未复位_REJECTED")
        void checkEligibility_notReset_rejected() {
            BuyContext context = buildEligibilityContext(
                    StockStrategyFitEnum.RANGING,
                    StockMaturityEnum.M3_SEASONED,
                    StockRiskLevelEnum.NONE,
                    Boolean.TRUE);

            TornStockSignalStateDO signalState = new TornStockSignalStateDO();
            signalState.setLastCloseType("TAKE_PROFIT");
            signalState.setResetObserved(false);

            EligibilityResult result = service.checkEligibility(context, signalState, null, false);

            assertEquals(StockEligibilityResultEnum.REJECTED, result.result());
            assertTrue(result.reasons().contains("RESET_NOT_OBSERVED"));
        }

        @Test
        @DisplayName("checkEligibility_同股活跃批次_REJECTED")
        void checkEligibility_sameStockActiveBatch_rejected() {
            BuyContext context = buildEligibilityContext(
                    StockStrategyFitEnum.RANGING,
                    StockMaturityEnum.M3_SEASONED,
                    StockRiskLevelEnum.NONE,
                    Boolean.TRUE);

            EligibilityResult result = service.checkEligibility(context, null, null, true);

            assertEquals(StockEligibilityResultEnum.REJECTED, result.result());
            assertTrue(result.reasons().contains("SAME_STOCK_ACTIVE"));
        }

        @Test
        @DisplayName("checkEligibility_strategyReady为false_REJECTED")
        void checkEligibility_strategyReadyFalse_rejected() {
            BuyContext context = buildEligibilityContext(
                    StockStrategyFitEnum.RANGING,
                    StockMaturityEnum.M3_SEASONED,
                    StockRiskLevelEnum.NONE,
                    Boolean.FALSE);

            EligibilityResult result = service.checkEligibility(context, null, null, false);

            assertEquals(StockEligibilityResultEnum.REJECTED, result.result());
            assertTrue(result.reasons().contains("DATA_NOT_READY"));
        }

        @Test
        @DisplayName("checkEligibility_全部通过_ALLOWED")
        void checkEligibility_allPassed_allowed() {
            BuyContext context = buildEligibilityContext(
                    StockStrategyFitEnum.RANGING,
                    StockMaturityEnum.M3_SEASONED,
                    StockRiskLevelEnum.NONE,
                    Boolean.TRUE);

            EligibilityResult result = service.checkEligibility(context, null, null, false);

            assertEquals(StockEligibilityResultEnum.ALLOWED, result.result());
            assertTrue(result.reasons().isEmpty());
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建候选信息，使用固定的主策略与命中策略列表。
     *
     * @param stocksId    股票ID
     * @param shortname   股票简称
     * @param qualityScore 质量分
     * @return 候选信息
     */
    private static CandidateInfo buildCandidate(int stocksId, String shortname, BigDecimal qualityScore) {
        return new CandidateInfo(
                stocksId,
                shortname,
                StockBuyStrategyEnum.DEEP_MEAN_REVERSION_BUY,
                List.of(StockBuyStrategyEnum.DEEP_MEAN_REVERSION_BUY.getCode()),
                qualityScore);
    }

    /**
     * 构建资格判断测试上下文，仅设置资格检查需要的字段，其余使用安全默认值。
     *
     * @param style          策略适配风格
     * @param maturity       成熟度
     * @param riskLevel      风险等级
     * @param strategyReady  策略特征数据是否就绪
     * @return 买入评估上下文
     */
    private static BuyContext buildEligibilityContext(StockStrategyFitEnum style,
                                                      StockMaturityEnum maturity,
                                                      StockRiskLevelEnum riskLevel,
                                                      Boolean strategyReady) {
        return new BuyContext(
                1001,
                "TEST",
                new BigDecimal("500"),
                new BigDecimal("500"),
                new BigDecimal("998"),
                new BigDecimal("1000"),
                new BigDecimal("-2.5"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("490"),
                new BigDecimal("510"),
                new BigDecimal("0.04"),
                new BigDecimal("0.05"),
                new BigDecimal("0.002"),
                new BigDecimal("0.95"),
                strategyReady,
                style,
                maturity,
                riskLevel
        );
    }
}
