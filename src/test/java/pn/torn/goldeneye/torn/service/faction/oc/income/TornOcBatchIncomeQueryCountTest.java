package pn.torn.goldeneye.torn.service.faction.oc.income;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.torn.model.faction.crime.income.BatchIncomeResult;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量收益计算SQL查询次数证据测试。
 *
 * <p>通过测试内注册的MyBatis计数拦截器，统计一次批次执行过程中对
 * {@code torn_faction_oc}、{@code torn_faction_oc_income}的SELECT次数，证明批次按批量方式
 * 加载候选与祖先关系、一次集合查询income业务键，查询次数不随“候选数×链长”放大。</p>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.08.04
 */
@SpringBootTest
@DisplayName("批量收益计算SQL查询次数证据测试")
class TornOcBatchIncomeQueryCountTest extends TornOcIncomeDbTestSupport {
    @Autowired
    private TornOcBatchIncomeService batchIncomeService;

    private static final Long FACTION_ID = 999004L;
    private static final Long USER_ID = 888005L;

    private List<String> originalRotationList;

    @BeforeEach
    void setUp() {
        originalRotationList = saveRotationList(FACTION_ID, List.of(
                TornConstants.OC_NAME_STACKING_THE_DECK, TornConstants.OC_NAME_ACE_IN_THE_HOLE,
                TornConstants.OC_NAME_MANIFEST_CRUELTY, TornConstants.OC_NAME_GONE_FISSION,
                TornConstants.OC_NAME_CRANE_REACTION));
        insertCoefficient(0L, TornConstants.OC_NAME_MANIFEST_CRUELTY, 8, "Hacker#1", 65);
        insertCoefficient(0L, TornConstants.OC_NAME_GONE_FISSION, 9, "Imitator#1", 70);
        insertCoefficient(0L, TornConstants.OC_NAME_CRANE_REACTION, 10, "Muscle#1", 75);
        coefficientManager.refreshCache();
        SqlCountInterceptor.reset();
    }

    @AfterEach
    void cleanup() {
        // 物理删除测试数据并清理测试系数，确保开发库干净
        physicalDeleteCreatedOcs();
        physicalDeleteFactionIncomeAndSummary(FACTION_ID);
        cleanupConfigsAndRefreshCache();
        restoreRotationList(FACTION_ID, originalRotationList);
        batchIncomeService.releaseFactionCalculateLock(FACTION_ID);
    }

    @Test
    @DisplayName("多候选批次SQL次数不随候选数×链长放大")
    void batchQueryCount_isBoundedByChainDepthNotCandidateCount() {
        // 3条三段链（Manifest Cruelty → Gone Fission → Crane Reaction），共9个OC、9个岗位
        int chainCount = 3;
        for (int i = 0; i < chainCount; i++) {
            LocalDateTime base = LocalDateTime.of(2026, 4, 1 + i, 10, 0);
            TornFactionOcDO root = createOc(FACTION_ID, null, TornConstants.OC_NAME_MANIFEST_CRUELTY, 8,
                    TornOcStatusEnum.SUCCESSFUL, base, 0L);
            TornFactionOcDO mid = createOc(FACTION_ID, root.getId(), TornConstants.OC_NAME_GONE_FISSION, 9,
                    TornOcStatusEnum.SUCCESSFUL, base.plusDays(1), 0L);
            TornFactionOcDO leaf = createOc(FACTION_ID, mid.getId(), TornConstants.OC_NAME_CRANE_REACTION, 10,
                    TornOcStatusEnum.SUCCESSFUL, base.plusDays(2), 1000000L);
            createSlot(root.getId(), USER_ID, "Hacker#1", 65, 50000L);
            createSlot(mid.getId(), USER_ID, "Imitator#1", 70, 30000L);
            createSlot(leaf.getId(), USER_ID, "Muscle#1", 75, 20000L);
        }

        BatchIncomeResult result = batchIncomeService.batchCalculateIncome(FACTION_ID,
                LocalDateTime.of(2026, 4, 10, 0, 0, 0));

        assertEquals(chainCount, result.successCount());
        int ocSelectCount = SqlCountInterceptor.ocSelectCount();
        int incomeSelectCount = SqlCountInterceptor.incomeSelectCount();
        // 批次门面：候选查询1次 + 祖先批量加载（每层1次，共2层，跨候选共享） + income键1次集合查询 + 岗位1次批量查询。
        // Worker独立事务内对每个叶子执行一次受控批量查询（getById、后继存在、链节点复验、income键、岗位），
        // 以及受影响月份汇总重算。总次数与节点数线性相关，而非候选数×链长的逐节点查询放大。
        assertTrue(ocSelectCount <= 28, "torn_faction_oc SELECT次数应受控，实际=" + ocSelectCount);
        assertTrue(incomeSelectCount <= 14, "torn_faction_oc_income SELECT次数应受控，实际=" + incomeSelectCount);
    }

    /**
     * 测试专用的MyBatis计数拦截器，统计对torn_faction_oc与torn_faction_oc_income的SELECT次数。
     */
    @TestConfiguration
    static class SqlCountConfig {
        @Bean
        public Interceptor sqlCountInterceptor() {
            return new SqlCountInterceptor();
        }
    }

    /**
     * SQL计数拦截器实现。
     */
    @Intercepts({
            @Signature(type = StatementHandler.class, method = "prepare",
                    args = {Connection.class, Integer.class})
    })
    public static class SqlCountInterceptor implements Interceptor {
        private static final AtomicInteger OC_SELECT = new AtomicInteger();
        private static final AtomicInteger INCOME_SELECT = new AtomicInteger();
        private static final Pattern OC_PATTERN = Pattern.compile("FROM\\s+torn_faction_oc\\b", Pattern.CASE_INSENSITIVE);
        private static final Pattern INCOME_PATTERN =
                Pattern.compile("FROM\\s+torn_faction_oc_income\\b", Pattern.CASE_INSENSITIVE);

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            try {
                StatementHandler handler = (StatementHandler) invocation.getTarget();
                BoundSql boundSql = handler.getBoundSql();
                String sql = boundSql.getSql();
                if (sql.trim().toUpperCase().startsWith("SELECT")) {
                    Matcher ocMatcher = OC_PATTERN.matcher(sql);
                    if (ocMatcher.find()) {
                        OC_SELECT.incrementAndGet();
                    }
                    Matcher incomeMatcher = INCOME_PATTERN.matcher(sql);
                    if (incomeMatcher.find()) {
                        INCOME_SELECT.incrementAndGet();
                    }
                }
            } catch (Exception ignored) {
                // 计数失败不影响测试
            }
            return result;
        }

        @Override
        public Object plugin(Object target) {
            return org.apache.ibatis.plugin.Plugin.wrap(target, this);
        }

        public static void reset() {
            OC_SELECT.set(0);
            INCOME_SELECT.set(0);
        }

        public static int ocSelectCount() {
            return OC_SELECT.get();
        }

        public static int incomeSelectCount() {
            return INCOME_SELECT.get();
        }
    }
}
