package pn.torn.goldeneye.torn.model.faction.crime.income;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.constants.torn.TornConstants;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OC收益排名查询参数构造与排除规则测试。
 *
 * <p>验证扁平排除规则（原有名单始终排除、新增名单按生效时间排除）在各类查询构造器中的生成结果，
 * 确保排行榜与个人明细共用同一套日期边界规则。排除规则与帮派集合由测试内桩数据注入，
 * 桩值与Liquibase种子等价。</p>
 *
 * @author Bai
 * @version 1.6.2
 * @since 2026.08.03
 */
@DisplayName("OC收益排名查询参数测试")
class OcBenefitRankingQueryTest {
    /**
     * 桩大锅饭帮派集合，与种子6帮派一致。
     */
    private static final List<Long> REASSIGN_FACTIONS = List.of(
            TornConstants.FACTION_PN_ID, TornConstants.FACTION_HP_ID, TornConstants.FACTION_CCRC_ID,
            TornConstants.FACTION_SH_ID, TornConstants.FACTION_NOV_ID, TornConstants.FACTION_BSU_ID);

    /**
     * PN新增名单，自2026-08-01起排除。
     */
    private static final List<String> PN_ADDED_NAMES = List.of(
            TornConstants.OC_NAME_LOCK_STOCK, TornConstants.OC_NAME_HOSTILE_TAKEOVER);

    /**
     * NOV新增名单，自2026-07-01起排除。
     */
    private static final List<String> NOV_ADDED_NAMES = List.of(
            TornConstants.OC_NAME_LOCK_STOCK, TornConstants.OC_NAME_STACKING_THE_DECK,
            TornConstants.OC_NAME_MANIFEST_CRUELTY, TornConstants.OC_NAME_GONE_FISSION,
            TornConstants.OC_NAME_ACE_IN_THE_HOLE, TornConstants.OC_NAME_HOSTILE_TAKEOVER,
            TornConstants.OC_NAME_CRANE_REACTION);

    /**
     * 构造与门面派生结构一致的桩排除规则（与种子6帮派等价）。
     *
     * @return Key为帮派ID的排除规则
     */
    private static Map<Long, List<FactionOcExclusion>> stubExclusionRules() {
        List<String> basicNames = List.of(
                TornConstants.OC_NAME_BREAK_THE_BANK, TornConstants.OC_NAME_CLINICAL_PRECISION,
                TornConstants.OC_NAME_BLAST_FROM_THE_PAST, TornConstants.OC_NAME_WINDOW_OF_OPPORTUNITY);
        return Map.of(
                TornConstants.FACTION_PN_ID, List.of(
                        new FactionOcExclusion(TornConstants.FACTION_PN_ID, List.of(
                                TornConstants.OC_NAME_ACE_IN_THE_HOLE, TornConstants.OC_NAME_STACKING_THE_DECK,
                                TornConstants.OC_NAME_BREAK_THE_BANK, TornConstants.OC_NAME_CLINICAL_PRECISION,
                                TornConstants.OC_NAME_BLAST_FROM_THE_PAST,
                                TornConstants.OC_NAME_WINDOW_OF_OPPORTUNITY), null),
                        new FactionOcExclusion(TornConstants.FACTION_PN_ID, PN_ADDED_NAMES,
                                LocalDateTime.of(2026, 8, 1, 0, 0, 0))),
                TornConstants.FACTION_NOV_ID, List.of(
                        new FactionOcExclusion(TornConstants.FACTION_NOV_ID,
                                List.copyOf(basicNames), null),
                        new FactionOcExclusion(TornConstants.FACTION_NOV_ID, NOV_ADDED_NAMES,
                                LocalDateTime.of(2026, 7, 1, 0, 0, 0))),
                TornConstants.FACTION_HP_ID, List.of(new FactionOcExclusion(TornConstants.FACTION_HP_ID,
                        List.copyOf(basicNames), null)),
                TornConstants.FACTION_CCRC_ID, List.of(new FactionOcExclusion(TornConstants.FACTION_CCRC_ID,
                        List.copyOf(basicNames), null)),
                TornConstants.FACTION_SH_ID, List.of(new FactionOcExclusion(TornConstants.FACTION_SH_ID,
                        List.copyOf(basicNames), null)),
                TornConstants.FACTION_BSU_ID, List.of(new FactionOcExclusion(TornConstants.FACTION_BSU_ID,
                        List.copyOf(basicNames), null)));
    }

    @Test
    @DisplayName("PN包含新增名单且排除规则带生效时间")
    void pnFaction_queryBuildsScheduledExclusion() {
        OcBenefitRankingQuery query =
                new OcBenefitRankingQuery(TornConstants.FACTION_PN_ID, 0L, LocalDate.of(2026, 8, 15),
                        REASSIGN_FACTIONS, stubExclusionRules());

        List<FactionOcExclusion> rules = query.getFactionOcExclusions();
        Map<String, FactionOcExclusion> ruleMap = toRuleMap(rules);

        // 原有名单始终排除
        FactionOcExclusion original = ruleMap.get(key(TornConstants.FACTION_PN_ID, "original"));
        assertNotNull(original);
        assertNull(original.getEffectiveFrom());
        assertTrue(original.getOcList().contains(TornConstants.OC_NAME_ACE_IN_THE_HOLE));

        // 新增名单自2026-08-01起排除
        FactionOcExclusion added = ruleMap.get(key(TornConstants.FACTION_PN_ID, "added"));
        assertNotNull(added);
        assertEquals(LocalDateTime.of(2026, 8, 1, 0, 0, 0), added.getEffectiveFrom());
        assertTrue(added.getOcList().contains(TornConstants.OC_NAME_LOCK_STOCK));
        assertTrue(added.getOcList().contains(TornConstants.OC_NAME_HOSTILE_TAKEOVER));
    }

    @Test
    @DisplayName("NOV排除规则包含七个新增OC且自2026-07-01生效")
    void novFaction_queryBuildsScheduledExclusion() {
        OcBenefitRankingQuery query =
                new OcBenefitRankingQuery(TornConstants.FACTION_NOV_ID, 0L, LocalDate.of(2026, 7, 15),
                        REASSIGN_FACTIONS, stubExclusionRules());

        List<FactionOcExclusion> rules = query.getFactionOcExclusions();
        Map<String, FactionOcExclusion> ruleMap = toRuleMap(rules);

        FactionOcExclusion added = ruleMap.get(key(TornConstants.FACTION_NOV_ID, "added"));
        assertNotNull(added);
        assertEquals(LocalDateTime.of(2026, 7, 1, 0, 0, 0), added.getEffectiveFrom());
        assertEquals(7, added.getOcList().size());
        for (String ocName : NOV_ADDED_NAMES) {
            assertTrue(added.getOcList().contains(ocName));
        }
    }

    @Test
    @DisplayName("其他大锅饭帮派仅包含始终排除规则")
    void hpFaction_onlyAlwaysExcludedRule() {
        OcBenefitRankingQuery query =
                new OcBenefitRankingQuery(TornConstants.FACTION_HP_ID, 0L, LocalDate.of(2026, 8, 15),
                        REASSIGN_FACTIONS, stubExclusionRules());

        assertEquals(1, query.getFactionOcExclusions().size());
        FactionOcExclusion rule = query.getFactionOcExclusions().getFirst();
        assertEquals(TornConstants.FACTION_HP_ID, rule.getFactionId());
        assertNull(rule.getEffectiveFrom());
        assertTrue(rule.getOcList().contains(TornConstants.OC_NAME_BREAK_THE_BANK));
    }

    @Test
    @DisplayName("非大锅饭帮派不应用任何排除规则")
    void normalFaction_emptyExclusions() {
        OcBenefitRankingQuery query = new OcBenefitRankingQuery(9999L, 0L, LocalDate.of(2026, 8, 15),
                REASSIGN_FACTIONS, stubExclusionRules());

        assertTrue(query.getFactionOcExclusions().isEmpty());
        assertTrue(query.isIncludeNormalBenefit());
        assertFalse(query.isIncludeReassignBenefit());
    }

    @Test
    @DisplayName("总榜包含所有大锅饭帮派的排除规则")
    void smthTotal_queryFlattensAllFactionRules() {
        OcBenefitRankingQuery query = new OcBenefitRankingQuery(0L, 123L, LocalDate.of(2026, 8, 15),
                REASSIGN_FACTIONS, stubExclusionRules());

        assertEquals(0L, query.getFactionId());
        assertTrue(query.getFactionOcExclusions().size() >= REASSIGN_FACTIONS.size());
        // 所有大锅饭帮派都出现在规则中
        for (Long fid : REASSIGN_FACTIONS) {
            assertTrue(query.getFactionOcExclusions().stream()
                    .anyMatch(rule -> fid.equals(rule.getFactionId())));
        }
    }

    @Test
    @DisplayName("个人明细构造器保留时间范围并应用所属帮派规则")
    void personalBenefit_queryKeepsDateRange() {
        LocalDateTime from = LocalDateTime.of(2026, 7, 1, 0, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 7, 31, 23, 59, 59);
        OcBenefitRankingQuery query = new OcBenefitRankingQuery(TornConstants.FACTION_NOV_ID, 2001L, from, to,
                REASSIGN_FACTIONS, stubExclusionRules());

        assertEquals(from, query.getFromDate());
        assertEquals(to, query.getToDate());
        assertEquals("2026-07", query.getYearMonth());
        assertEquals(2001L, query.getUserId());
        assertEquals(2, query.getFactionOcExclusions().size());
        assertTrue(query.getFactionOcExclusions().stream()
                .allMatch(rule -> TornConstants.FACTION_NOV_ID == rule.getFactionId()));
    }

    private Map<String, FactionOcExclusion> toRuleMap(List<FactionOcExclusion> rules) {
        return rules.stream()
                .collect(Collectors.toMap(rule -> key(rule.getFactionId(),
                        rule.getEffectiveFrom() == null ? "original" : "added"), Function.identity()));
    }

    private String key(Long factionId, String type) {
        return factionId + "#" + type;
    }
}
