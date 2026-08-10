package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMonthlyStateStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMonthlyStateDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.utils.JsonUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 月度状态Mapper/DAO真实PostgreSQL集成测试。
 * <p>
 * 使用远端未来月份(2099-09-01)作为隔离测试月,通过{@code @Transactional}回滚
 * 保证开发库零残留,不直接编写任何SQL。验证:
 * <ul>
 *   <li>首次插入成功,返回实际插入行数;</li>
 *   <li>同输入重复执行为0行,不抛重复键异常;</li>
 *   <li>DRAFT与CONFIRMED状态都能阻止同股票同月重复插入;</li>
 *   <li>部分唯一索引 {@code uk_stock_monthly_state_stock_month} 仍存在且有效:
 *       其存在性由{@code ON CONFLICT}子句执行前提隐式保证,数据库层拒绝由
 *       MyBatis-Plus标准insert触发{@link DuplicateKeyException}显式验证。</li>
 * </ul>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
@SpringBootTest
@Transactional
@DisplayName("月度状态Mapper真实PostgreSQL集成测试")
class TornStockMonthlyStateMapperTest {

    @Autowired
    private TornStockMonthlyStateDAO monthlyStateDao;

    /**
     * 隔离测试月(远离生产数据)
     */
    private static final LocalDate TEST_MONTH = LocalDate.of(2099, 9, 1);

    @Test
    @DisplayName("真实PG_首次插入成功并返回实际插入数,重复执行0行不抛异常")
    void insertDraftStatesIgnoreConflict_firstInsertSucceedsAndRepeatReturnsZero() {
        List<TornStockMonthlyStateDO> drafts = List.of(
                buildDraft(101, "T101"), buildDraft(102, "T102"));

        int first = monthlyStateDao.insertDraftStatesIgnoreConflict(drafts);

        assertEquals(2, first, "首次插入应返回实际插入行数2");
        assertEquals(2, countRows(), "库中应有2行");

        int second = monthlyStateDao.insertDraftStatesIgnoreConflict(drafts);

        assertEquals(0, second, "重复插入同输入应被DO NOTHING吸收返回0");
        assertEquals(2, countRows(), "重复插入后库中仍应只有2行");
    }

    @Test
    @DisplayName("真实PG_DRAFT与CONFIRMED状态都能阻止同股票同月重复插入")
    void insertDraftStatesIgnoreConflict_existingDraftAndConfirmedBothBlockDuplicate() {
        // 先插入101的DRAFT草稿
        assertEquals(1, monthlyStateDao.insertDraftStatesIgnoreConflict(List.of(buildDraft(101, "T101"))));

        // 再次插入101的DRAFT: 应被已存在DRAFT阻止
        assertEquals(0, monthlyStateDao.insertDraftStatesIgnoreConflict(List.of(buildDraft(101, "T101"))));

        // 101的DRAFT已存在,插入同股票同月的CONFIRMED也应被阻止
        assertEquals(0, monthlyStateDao.insertDraftStatesIgnoreConflict(List.of(buildConfirmed(101, "T101"))));

        // 102先插入CONFIRMED状态,再插入102的DRAFT应被阻止
        assertEquals(1, monthlyStateDao.insertDraftStatesIgnoreConflict(List.of(buildConfirmed(102, "T102"))));
        assertEquals(0, monthlyStateDao.insertDraftStatesIgnoreConflict(List.of(buildDraft(102, "T102"))));

        assertEquals(2, countRows(), "两股各应仅1行");
    }

    @Test
    @DisplayName("真实PG_部分唯一索引仍存在且在数据库层拒绝重复行")
    void uniqueIndex_stillExistsAndEnforcesUniqueness() {
        assertEquals(1, monthlyStateDao.insertDraftStatesIgnoreConflict(List.of(buildDraft(103, "T103"))));
        assertEquals(1, countRows(), "库中应仅1行");

        // 通过MyBatis-Plus标准insert(不携带ON CONFLICT)直接插入同股票同月重复行,
        // 若部分唯一索引缺失则此处插入成功,断言失败即捕获索引被删除的风险。
        TornStockMonthlyStateDO duplicate = buildDraft(103, "T103");
        assertThrows(DuplicateKeyException.class,
                () -> monthlyStateDao.save(duplicate),
                "数据库层部分唯一索引必须拒绝同股票同月重复行");
    }

    @Test
    @DisplayName("真实PG_重算UPDATE仅命中DRAFT且非人工覆盖记录")
    void recalculateDraftStates_onlyUpdatesDraftNonOverride() {
        // 股票201: 普通DRAFT(manual_override=false),应被重算更新
        TornStockMonthlyStateDO normalDraft = buildDraft(201, "T201");
        normalDraft.setEvidenceStartTime(LocalDateTime.of(2025, 1, 1, 0, 0));
        normalDraft.setEvidenceEndTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        normalDraft.setStrategyFitPrior("OLD");
        // 股票202: 人工覆盖DRAFT(manual_override=true),不得被重算更新
        TornStockMonthlyStateDO overrideDraft = buildDraft(202, "T202");
        overrideDraft.setStrategyFitPrior("OVERRIDE");
        overrideDraft.setManualOverride(true);
        overrideDraft.setOverrideReason("MANUAL");
        // 股票203: CONFIRMED,不得被重算更新
        TornStockMonthlyStateDO confirmed = buildConfirmed(203, "T203");
        confirmed.setStrategyFitPrior("CONFIRMED");
        // 股票204: RETIRED,不得被重算更新
        TornStockMonthlyStateDO retired = buildDraft(204, "T204");
        retired.setStrategyFitPrior("RETIRED");
        retired.setStateStatus(StockMonthlyStateStatusEnum.RETIRED.getCode());

        assertEquals(4, monthlyStateDao.insertDraftStatesIgnoreConflict(
                List.of(normalDraft, overrideDraft, confirmed, retired)));

        // 读取真实主键,重算UPDATE按主键匹配并自带DRAFT+manual_override=false谓词;
        // 即使把202/203/204误传入,也不得更新其状态/策略。
        List<TornStockMonthlyStateDO> existing = monthlyStateDao.lambdaQuery()
                .eq(TornStockMonthlyStateDO::getEffectiveMonth, TEST_MONTH)
                .list();
        List<TornStockMonthlyStateDO> attemptAll = existing.stream()
                .map(state -> {
                    TornStockMonthlyStateDO candidate = buildDraft(state.getStocksId(), "T" + state.getStocksId());
                    candidate.setId(state.getId());
                    candidate.setStocksId(state.getStocksId());
                    candidate.setStrategyFitPrior("NEW");
                    candidate.setEvidenceStartTime(LocalDateTime.of(2025, 1, 1, 0, 0));
                    candidate.setEvidenceEndTime(LocalDateTime.of(2026, 1, 1, 0, 0));
                    return candidate;
                })
                .toList();

        int updated = monthlyStateDao.recalculateDraftStates(attemptAll);

        assertEquals(1, updated, "重算UPDATE应仅命中201(DRAFT且非人工覆盖)");

        List<TornStockMonthlyStateDO> after = monthlyStateDao.lambdaQuery()
                .eq(TornStockMonthlyStateDO::getEffectiveMonth, TEST_MONTH)
                .list();
        for (TornStockMonthlyStateDO state : after) {
            if (state.getStocksId() == 201) {
                assertEquals("NEW", state.getStrategyFitPrior(), "普通DRAFT应被重算更新");
            } else if (state.getStocksId() == 202) {
                assertEquals("OVERRIDE", state.getStrategyFitPrior(), "人工覆盖DRAFT不得被重算修改");
                assertEquals(Boolean.TRUE, state.getManualOverride(), "人工覆盖标记不得被重算清除");
            } else if (state.getStocksId() == 203) {
                assertEquals("CONFIRMED", state.getStrategyFitPrior(), "CONFIRMED不得被重算修改");
                assertEquals(StockMonthlyStateStatusEnum.CONFIRMED.getCode(), state.getStateStatus(),
                        "CONFIRMED状态不得被降级");
            } else if (state.getStocksId() == 204) {
                assertEquals("RETIRED", state.getStrategyFitPrior(), "RETIRED不得被重算修改");
                assertEquals(StockMonthlyStateStatusEnum.RETIRED.getCode(), state.getStateStatus(),
                        "RETIRED状态不得被降级");
            }
        }
    }

    /**
     * 查询测试月当前有效行数。
     *
     * @return 行数
     */
    private int countRows() {
        return monthlyStateDao.selectExistingStockIdsByMonth(TEST_MONTH).size();
    }

    /**
     * 构建CONFIRMED月度状态DO(全部NOT NULL字段填充,满足ck_monthly_confirmed_complete)。
     *
     * @param stocksId  股票ID
     * @param shortname 股票简称
     * @return 已确认DO
     */
    private TornStockMonthlyStateDO buildConfirmed(int stocksId, String shortname) {
        TornStockMonthlyStateDO state = buildDraft(stocksId, shortname);
        state.setEvidenceStartTime(LocalDateTime.of(2025, 1, 1, 0, 0));
        state.setEvidenceEndTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        state.setStateStatus(StockMonthlyStateStatusEnum.CONFIRMED.getCode());
        state.setConfirmedAt(LocalDateTime.now());
        state.setConfirmedBy("IT_TEST");
        return state;
    }

    /**
     * 构建DRAFT月度状态DO(全部NOT NULL字段填充)。
     *
     * @param stocksId  股票ID
     * @param shortname 股票简称
     * @return 草稿DO
     */
    private TornStockMonthlyStateDO buildDraft(int stocksId, String shortname) {
        TornStockMonthlyStateDO state = new TornStockMonthlyStateDO();
        state.setStocksId(stocksId);
        state.setStocksShortname(shortname);
        state.setEffectiveMonth(TEST_MONTH);
        state.setStrategyFitPrior("STEADY");
        state.setMaturity(StockMaturityEnum.M4_MATURE.getCode());
        state.setRiskLevel(StockRiskLevelEnum.NONE.getCode());
        state.setSuggestedPersonality("STEADY");
        state.setPreviousPersonality(null);
        state.setManualOverride(false);
        state.setOverrideReason(null);
        state.setMetricSnapshot(JsonUtils.objToJson(java.util.Map.of("stocksId", stocksId, "stocksShortname", shortname)));
        state.setPersonalityRuleVersion("1.0.0");
        state.setRiskRuleVersion("1.0.0");
        state.setEvidenceStartTime(null);
        state.setEvidenceEndTime(null);
        state.setStateStatus(StockMonthlyStateStatusEnum.DRAFT.getCode());
        state.setCalculatedAt(LocalDateTime.now());
        state.setConfirmedAt(null);
        state.setConfirmedBy(null);
        return state;
    }
}
