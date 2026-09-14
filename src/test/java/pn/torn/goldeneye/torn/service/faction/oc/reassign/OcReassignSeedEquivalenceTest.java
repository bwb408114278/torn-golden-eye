package pn.torn.goldeneye.torn.service.faction.oc.reassign;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import pn.torn.goldeneye.constants.torn.enums.TornOcIncomeModeEnum;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcReassignFactionDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcReassignOcDAO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcReassignFactionDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcReassignOcDO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 大锅饭Liquibase种子等价性测试（迁移等价生命线）。
 *
 * <p>Liquibase种子随启动落库后，硬编码断言6帮派模式映射与每帮派范围行（名称/级别/生效时间）
 * 与技术设计5.3节表格逐行一致。只读不写库，无需回滚。</p>
 *
 * @author Bai
 * @version 1.6.2
 * @since 2026.09.14
 */
@SpringBootTest
@Tag("shared-db")
@DisplayName("大锅饭种子等价性测试")
class OcReassignSeedEquivalenceTest {
    private static final LocalDateTime AUG_FROM = LocalDateTime.of(2026, 8, 1, 0, 0, 0);
    private static final LocalDateTime JUL_FROM = LocalDateTime.of(2026, 7, 1, 0, 0, 0);

    /**
     * 5.3节帮级行期望：帮派ID到收益模式
     */
    private static final Map<Long, TornOcIncomeModeEnum> EXPECTED_MODES = Map.of(
            20465L, TornOcIncomeModeEnum.COEFFICIENT,
            2095L, TornOcIncomeModeEnum.COEFFICIENT,
            27902L, TornOcIncomeModeEnum.COEFFICIENT,
            36134L, TornOcIncomeModeEnum.COEFFICIENT,
            16335L, TornOcIncomeModeEnum.EQUAL,
            11796L, TornOcIncomeModeEnum.EQUAL);

    /**
     * 5.3节范围行期望：帮派ID到(OC名称, 级别, 生效时间)列表
     */
    private static final Map<Long, List<ScopeExpectation>> EXPECTED_SCOPES = Map.of(
            20465L, List.of(
                    new ScopeExpectation("Ace in the Hole", 9, null),
                    new ScopeExpectation("Stacking the Deck", 8, null),
                    new ScopeExpectation("Break the Bank", 8, null),
                    new ScopeExpectation("Clinical Precision", 8, null),
                    new ScopeExpectation("Blast from the Past", 7, null),
                    new ScopeExpectation("Window of Opportunity", 7, null),
                    new ScopeExpectation("Lock Stock", 8, AUG_FROM),
                    new ScopeExpectation("Hostile Takeover", 9, AUG_FROM)),
            2095L, List.of(
                    new ScopeExpectation("Break the Bank", 8, null),
                    new ScopeExpectation("Clinical Precision", 8, null),
                    new ScopeExpectation("Blast from the Past", 7, null),
                    new ScopeExpectation("Window of Opportunity", 7, null)),
            27902L, List.of(
                    new ScopeExpectation("Break the Bank", 8, null),
                    new ScopeExpectation("Clinical Precision", 8, null),
                    new ScopeExpectation("Blast from the Past", 7, null),
                    new ScopeExpectation("Window of Opportunity", 7, null)),
            36134L, List.of(
                    new ScopeExpectation("Break the Bank", 8, null),
                    new ScopeExpectation("Clinical Precision", 8, null),
                    new ScopeExpectation("Blast from the Past", 7, null),
                    new ScopeExpectation("Window of Opportunity", 7, null)),
            16335L, List.of(
                    new ScopeExpectation("Break the Bank", 8, null),
                    new ScopeExpectation("Clinical Precision", 8, null),
                    new ScopeExpectation("Blast from the Past", 7, null),
                    new ScopeExpectation("Window of Opportunity", 7, null),
                    new ScopeExpectation("Lock Stock", 8, JUL_FROM),
                    new ScopeExpectation("Stacking the Deck", 8, JUL_FROM),
                    new ScopeExpectation("Manifest Cruelty", 8, JUL_FROM),
                    new ScopeExpectation("Gone Fission", 9, JUL_FROM),
                    new ScopeExpectation("Ace in the Hole", 9, JUL_FROM),
                    new ScopeExpectation("Hostile Takeover", 9, JUL_FROM),
                    new ScopeExpectation("Crane Reaction", 10, JUL_FROM)),
            11796L, List.of(
                    new ScopeExpectation("Break the Bank", 8, null),
                    new ScopeExpectation("Clinical Precision", 8, null),
                    new ScopeExpectation("Blast from the Past", 7, null),
                    new ScopeExpectation("Window of Opportunity", 7, null)));

    @Autowired
    private TornSettingOcReassignFactionDAO reassignFactionDao;
    @Autowired
    private TornSettingOcReassignOcDAO reassignOcDao;

    @Test
    @DisplayName("帮级行：6帮派模式与启用状态逐行等价")
    void factionSeed_matchesDesignTable() {
        List<TornSettingOcReassignFactionDO> rows = reassignFactionDao.lambdaQuery()
                .eq(TornSettingOcReassignFactionDO::getDeleted, 0)
                .list();

        assertEquals(6, rows.size(), "实际帮级行: " + rows);
        for (TornSettingOcReassignFactionDO row : rows) {
            TornOcIncomeModeEnum expectedMode = EXPECTED_MODES.get(row.getFactionId());
            assertNotNull(expectedMode, "非期望帮派: " + row.getFactionId());
            assertEquals(expectedMode, TornOcIncomeModeEnum.of(row.getIncomeMode()),
                    "帮派" + row.getFactionId() + "模式不符");
            assertEquals(Boolean.TRUE, row.getEnabled(), "帮派" + row.getFactionId() + "应启用");
        }
    }

    @Test
    @DisplayName("范围行：每帮派名称/级别/生效时间逐行等价")
    void scopeSeed_matchesDesignTable() {
        List<TornSettingOcReassignOcDO> rows = reassignOcDao.lambdaQuery()
                .eq(TornSettingOcReassignOcDO::getDeleted, 0)
                .list();

        Map<Long, List<TornSettingOcReassignOcDO>> rowsByFaction = rows.stream()
                .collect(Collectors.groupingBy(TornSettingOcReassignOcDO::getFactionId));
        assertEquals(EXPECTED_SCOPES.keySet(), rowsByFaction.keySet(), "帮派集合不符");
        for (Map.Entry<Long, List<ScopeExpectation>> entry : EXPECTED_SCOPES.entrySet()) {
            List<ScopeExpectation> actual = rowsByFaction.get(entry.getKey()).stream()
                    .map(row -> new ScopeExpectation(row.getOcName(), row.getRank(), row.getEffectiveFrom()))
                    .toList();
            assertEquals(entry.getValue().size(), actual.size(),
                    "帮派" + entry.getKey() + "行数不符: " + actual);
            assertTrue(actual.containsAll(entry.getValue()),
                    "帮派" + entry.getKey() + "范围行与设计不符: " + actual);
        }
    }

    /**
     * 范围行期望值。
     *
     * @param ocName        OC名称
     * @param rank          OC级别
     * @param effectiveFrom 生效时间，null表示始终
     */
    private record ScopeExpectation(String ocName, Integer rank, LocalDateTime effectiveFrom) {
    }
}
