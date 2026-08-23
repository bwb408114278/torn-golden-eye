package pn.torn.goldeneye.torn.service.stocks.alert.monthly;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMonthlyStateStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMonthlyStateDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.utils.JsonUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 月度状态自动确认条件UPDATE真实PostgreSQL集成测试。
 * <p>
 * 验证 {@code autoConfirmDraftStates} 条件UPDATE对人工覆盖/并发状态变更的读-写竞态守卫:
 * <ul>
 *   <li><b>竞态守卫</b>: 种子DRAFT(manual_override=false)提交后,先在事务A读取并构造过期确认对象,
 *       再在事务B中人工覆盖该行(manual_override=true, confirmed_by=HUMAN)并提交,最后携带过期对象执行
 *       条件UPDATE,数据库谓词看到manual_override=true拒绝更新,返回0且不覆盖人工结果;</li>
 *   <li><b>已确认/已退役/人工覆盖返回0</b>: 三种行提交条件UPDATE均不满足谓词,返回0且不降级/覆盖;</li>
 *   <li><b>普通完整DRAFT确认</b>: 经{@link StockMonthlyStateInitService}全链路确认返回实际受影响行数1,
 *       行变为CONFIRMED且confirmed_by=SYSTEM。</li>
 * </ul>
 * 使用隔离股票ID(2097001..2097005)与隔离未来月份(2099-01-01),{@code @AfterEach}按
 * {@code stocks_id IN (测试股票) AND effective_month = 测试月} 精确物理DELETE,不触碰其他月份/股票。
 * 事务间顺序由{@link TransactionTemplate}控制,无需真实并发线程即可确定性复现该竞态。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.09
 */
@SpringBootTest
@Tag("shared-db")
@DisplayName("月度状态自动确认条件UPDATE真实PostgreSQL集成测试")
class TornStockMonthlyStateAutoConfirmMapperTest {

    @Autowired
    private TornStockMonthlyStateDAO monthlyStateDao;
    @Autowired
    private StockMonthlyStateInitService monthlyStateInitService;
    @Autowired
    private NamedParameterJdbcTemplate namedJdbcTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * 隔离测试月(生产数据之外,校验约束要求每月1日)。
     */
    private static final LocalDate TEST_MONTH = LocalDate.of(2099, 1, 1);

    /**
     * 隔离股票ID集合(远离生产股票1..35)。
     */
    private static final List<Integer> TEST_STOCKS = List.of(2097001, 2097002, 2097003, 2097004, 2097005);

    @AfterEach
    void cleanupTestRows() {
        namedJdbcTemplate.update(
                "DELETE FROM torn_stock_monthly_state "
                        + "WHERE stocks_id IN (:stocks) AND effective_month = :month",
                Map.of("stocks", TEST_STOCKS, "month", TEST_MONTH));
    }

    @Test
    @DisplayName("真实PG_读-写竞态_人工覆盖在SELECT与UPDATE之间提交,过期对象被拒绝返回0")
    void autoConfirmDraftStates_readWriteRace_humanOverrideBetweenReadAndWriteWins() {
        // 事务A: 提交可自动确认的DRAFT种子(manual_override=false, confirmed_by=null)
        transactionTemplate.executeWithoutResult(status ->
                monthlyStateDao.insertDraftStatesIgnoreConflict(List.of(buildAutoConfirmableDraft(2097001))));

        // 模拟服务SELECT后内存中的过期确认对象(此时行尚未被人工覆盖,预过滤通过)
        TornStockMonthlyStateDO staleCandidate = monthlyStateDao.lambdaQuery()
                .eq(TornStockMonthlyStateDO::getStocksId, 2097001)
                .eq(TornStockMonthlyStateDO::getEffectiveMonth, TEST_MONTH)
                .one();
        assertNotNull(staleCandidate, "种子行应存在");
        Long rowId = staleCandidate.getId();
        staleCandidate.setStateStatus(StockMonthlyStateStatusEnum.CONFIRMED.getCode());
        staleCandidate.setConfirmedAt(LocalDateTime.of(2099, 1, 2, 0, 0));
        staleCandidate.setConfirmedBy("SYSTEM");

        // 事务B: 人工在SELECT与UPDATE之间覆盖该DRAFT并提交
        transactionTemplate.executeWithoutResult(status -> {
            TornStockMonthlyStateDO override = new TornStockMonthlyStateDO();
            override.setId(rowId);
            override.setManualOverride(true);
            override.setOverrideReason("MANUAL");
            override.setConfirmedBy("HUMAN");
            monthlyStateDao.updateById(override);
        });

        // 携带过期对象执行条件UPDATE: 数据库谓词看到manual_override=true拒绝更新
        int updated = monthlyStateDao.autoConfirmDraftStates(List.of(staleCandidate));

        assertEquals(0, updated, "被人工覆盖的行不得被过期对象确认,返回0");
        TornStockMonthlyStateDO after = monthlyStateDao.getById(rowId);
        assertEquals(StockMonthlyStateStatusEnum.DRAFT.getCode(), after.getStateStatus(),
                "状态必须保持DRAFT,不得被降级或改写");
        assertEquals(Boolean.TRUE, after.getManualOverride(), "人工覆盖标记必须保留");
        assertEquals("HUMAN", after.getConfirmedBy(), "确认人必须保留人工标识,不得被改写为SYSTEM");
        assertNull(after.getConfirmedAt(), "confirmed_at必须保持未确认,不得被系统写入");
    }

    @Test
    @DisplayName("真实PG_已确认/已退役/人工覆盖DRAFT提交条件UPDATE返回0且不降级不覆盖")
    void autoConfirmDraftStates_confirmedRetiredOrOverridden_returnsZeroWithoutOverwrite() {
        transactionTemplate.executeWithoutResult(status -> {
            // 2097002: 已CONFIRMED,不得被改写
            TornStockMonthlyStateDO confirmed = buildAutoConfirmableDraft(2097002);
            confirmed.setStateStatus(StockMonthlyStateStatusEnum.CONFIRMED.getCode());
            confirmed.setConfirmedAt(LocalDateTime.of(2099, 1, 2, 0, 0));
            confirmed.setConfirmedBy("HUMAN");
            // 2097003: 已RETIRED,不得被改写
            TornStockMonthlyStateDO retired = buildAutoConfirmableDraft(2097003);
            retired.setStateStatus(StockMonthlyStateStatusEnum.RETIRED.getCode());
            // 2097004: 人工覆盖DRAFT(manual_override=true),不得被改写
            TornStockMonthlyStateDO overridden = buildAutoConfirmableDraft(2097004);
            overridden.setManualOverride(true);
            overridden.setOverrideReason("MANUAL");
            overridden.setConfirmedBy("HUMAN");
            monthlyStateDao.insertDraftStatesIgnoreConflict(List.of(confirmed, retired, overridden));
        });

        List<TornStockMonthlyStateDO> existing = monthlyStateDao.lambdaQuery()
                .eq(TornStockMonthlyStateDO::getEffectiveMonth, TEST_MONTH)
                .list();
        assertEquals(3, existing.size(), "种子应插入3行");

        // 构造过期确认对象: 即使把不满足谓词的行全部传入,也不得有任何一行被更新
        List<TornStockMonthlyStateDO> staleCandidates = existing.stream()
                .map(state -> {
                    TornStockMonthlyStateDO candidate = new TornStockMonthlyStateDO();
                    candidate.setId(state.getId());
                    candidate.setStateStatus(StockMonthlyStateStatusEnum.CONFIRMED.getCode());
                    candidate.setConfirmedAt(LocalDateTime.of(2099, 1, 3, 0, 0));
                    candidate.setConfirmedBy("SYSTEM");
                    return candidate;
                })
                .toList();

        int updated = monthlyStateDao.autoConfirmDraftStates(staleCandidates);

        assertEquals(0, updated, "三行均不满足DRAFT且manual_override=false,返回0");
        for (TornStockMonthlyStateDO state : existing) {
            TornStockMonthlyStateDO after = monthlyStateDao.getById(state.getId());
            assertEquals(state.getStateStatus(), after.getStateStatus(), "状态不得被降级或改写");
            if (state.getStocksId() == 2097002 || state.getStocksId() == 2097004) {
                assertEquals("HUMAN", after.getConfirmedBy(), "既有确认人不得被改写为SYSTEM");
            }
            if (state.getStocksId() == 2097003) {
                assertNull(after.getConfirmedBy(), "未确认行确认人不得被写入");
                assertNull(after.getConfirmedAt(), "未确认行confirmed_at不得被写入");
            }
            if (state.getStocksId() == 2097004) {
                assertEquals(Boolean.TRUE, after.getManualOverride(), "人工覆盖标记必须保留");
            }
        }
    }

    @Test
    @DisplayName("真实PG_普通完整DRAFT经服务自动确认返回实际受影响行数1且写SYSTEM")
    void autoConfirmDraftStates_normalDraft_confirmedBySystemEndToEnd() {
        transactionTemplate.executeWithoutResult(status ->
                monthlyStateDao.insertDraftStatesIgnoreConflict(List.of(buildAutoConfirmableDraft(2097005))));

        int updated = monthlyStateInitService.autoConfirmDraftStates(TEST_MONTH);

        assertEquals(1, updated, "普通完整DRAFT应实际自动确认1行");
        TornStockMonthlyStateDO after = monthlyStateDao.lambdaQuery()
                .eq(TornStockMonthlyStateDO::getStocksId, 2097005)
                .eq(TornStockMonthlyStateDO::getEffectiveMonth, TEST_MONTH)
                .one();
        assertNotNull(after, "确认后行应存在");
        assertEquals(StockMonthlyStateStatusEnum.CONFIRMED.getCode(), after.getStateStatus(),
                "状态应流转为CONFIRMED");
        assertEquals("SYSTEM", after.getConfirmedBy(), "自动确认人应为SYSTEM");
        assertNotNull(after.getConfirmedAt(), "confirmed_at应被写入");
    }

    /**
     * 构建满足自动确认条件的DRAFT(冻结版本、数据完整、无人工覆盖、含指标快照)。
     *
     * @param stocksId 股票ID
     * @return 自动可确认DRAFT
     */
    private TornStockMonthlyStateDO buildAutoConfirmableDraft(int stocksId) {
        TornStockMonthlyStateDO state = new TornStockMonthlyStateDO();
        state.setStocksId(stocksId);
        state.setStocksShortname("I" + stocksId);
        state.setEffectiveMonth(TEST_MONTH);
        state.setStrategyFitPrior("STEADY");
        state.setMaturity(StockMaturityEnum.M4_MATURE.getCode());
        state.setRiskLevel(StockRiskLevelEnum.NONE.getCode());
        state.setSuggestedPersonality("STEADY");
        state.setPreviousPersonality(null);
        state.setManualOverride(false);
        state.setOverrideReason(null);
        state.setMetricSnapshot(JsonUtils.objToJson(Map.of("rawPersonality", "STEADY", "stocksId", stocksId)));
        state.setPersonalityRuleVersion(StockMonthlyStateCalculator.PERSONALITY_RULE_VERSION);
        state.setRiskRuleVersion(StockMonthlyStateCalculator.RISK_RULE_VERSION);
        state.setEvidenceStartTime(LocalDateTime.of(2025, 1, 1, 0, 0));
        state.setEvidenceEndTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        state.setStateStatus(StockMonthlyStateStatusEnum.DRAFT.getCode());
        state.setCalculatedAt(LocalDateTime.now());
        state.setConfirmedAt(null);
        state.setConfirmedBy(null);
        return state;
    }
}
