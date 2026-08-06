package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 月度状态Mapper/DAO真实PostgreSQL集成测试。
 * <p>
 * 使用远端未来月份(2099-09-01)作为隔离测试月,验证:
 * <ul>
 *   <li>首次插入成功,返回实际插入行数;</li>
 *   <li>同输入重复执行为0行,不抛重复键异常;</li>
 *   <li>DRAFT与CONFIRMED状态都能阻止同股票同月重复插入;</li>
 *   <li>部分唯一索引 {@code uk_stock_monthly_state_stock_month} 仍然存在且有效。</li>
 * </ul>
 * {@code @AfterEach} 通过JdbcTemplate物理删除测试月份数据,保证开发库零残留。
 *
 * @author Bai
 * @version 1.2.13
 * @since 2026.08.06
 */
@SpringBootTest
@DisplayName("月度状态Mapper真实PostgreSQL集成测试")
class TornStockMonthlyStateMapperIT {

    @Autowired
    private TornStockMonthlyStateDAO monthlyStateDao;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 隔离测试月(远离生产数据)
     */
    private static final LocalDate TEST_MONTH = LocalDate.of(2099, 9, 1);

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM torn_stock_monthly_state WHERE effective_month = ?", TEST_MONTH);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM torn_stock_monthly_state WHERE effective_month = ?", TEST_MONTH);
    }

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
        TornStockMonthlyStateDO draft101 = buildDraft(101, "T101");
        assertEquals(1, monthlyStateDao.insertDraftStatesIgnoreConflict(List.of(draft101)));

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
    @DisplayName("真实PG_部分唯一索引仍然存在且有效")
    void uniqueIndex_stillExistsAndEnforcesUniqueness() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'torn_stock_monthly_state'",
                String.class);
        assertTrue(indexes.contains("uk_stock_monthly_state_stock_month"),
                "部分唯一索引uk_stock_monthly_state_stock_month必须存在,实际: " + indexes);

        assertEquals(1, monthlyStateDao.insertDraftStatesIgnoreConflict(List.of(buildDraft(103, "T103"))));
        // 原生INSERT绕过程序直接触发索引冲突,证明唯一约束在数据库层仍有效
        int thrown = 0;
        try {
            jdbcTemplate.update(
                    "INSERT INTO torn_stock_monthly_state "
                            + "(stocks_id, stocks_shortname, effective_month, strategy_fit_prior, maturity, risk_level, "
                            + " suggested_personality, previous_personality, manual_override, override_reason, "
                            + " metric_snapshot, personality_rule_version, risk_rule_version, "
                            + " evidence_start_time, evidence_end_time, state_status, calculated_at, confirmed_at, confirmed_by, deleted) "
                            + "VALUES (103, 'T103', ?, 'STEADY', 'M4_MATURE', 'NONE', 'STEADY', NULL, false, NULL, "
                            + " '{}'::jsonb, '1.0.0', '1.0.0', NULL, NULL, 'DRAFT', ?, NULL, NULL, 0)",
                    TEST_MONTH, LocalDateTime.now());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            thrown = 1;
        }
        assertEquals(1, thrown, "数据库层唯一索引应拒绝重复行");
        assertEquals(1, countRows(), "库中应仍只有1行");
    }

    /**
     * 查询测试月当前行数。
     *
     * @return 行数
     */
    private int countRows() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM torn_stock_monthly_state WHERE effective_month = ?", Integer.class, TEST_MONTH);
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
