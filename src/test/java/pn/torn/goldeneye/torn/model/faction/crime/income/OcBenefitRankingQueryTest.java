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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OC收益排名查询参数构造与排除规则测试。
 *
 * <p>验证扁平排除规则（原有名单始终排除、新增名单按生效时间排除）在各类查询构造器中的生成结果，
 * 确保排行榜与个人明细共用同一套日期边界规则。</p>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.08.03
 */
@DisplayName("OC收益排名查询参数测试")
class OcBenefitRankingQueryTest {

    @Test
    @DisplayName("PN包含新增名单且排除规则带生效时间")
    void pnFaction_queryBuildsScheduledExclusion() {
        OcBenefitRankingQuery query =
                new OcBenefitRankingQuery(TornConstants.FACTION_PN_ID, 0L, LocalDate.of(2026, 8, 15));

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
                new OcBenefitRankingQuery(TornConstants.FACTION_NOV_ID, 0L, LocalDate.of(2026, 7, 15));

        List<FactionOcExclusion> rules = query.getFactionOcExclusions();
        Map<String, FactionOcExclusion> ruleMap = toRuleMap(rules);

        FactionOcExclusion added = ruleMap.get(key(TornConstants.FACTION_NOV_ID, "added"));
        assertNotNull(added);
        assertEquals(LocalDateTime.of(2026, 7, 1, 0, 0, 0), added.getEffectiveFrom());
        assertEquals(7, added.getOcList().size());
        for (String ocName : TornConstants.NOV_ADDED_ROTATION_OC_NAME) {
            assertTrue(added.getOcList().contains(ocName));
        }
    }

    @Test
    @DisplayName("其他大锅饭帮派仅包含始终排除规则")
    void hpFaction_onlyAlwaysExcludedRule() {
        OcBenefitRankingQuery query =
                new OcBenefitRankingQuery(TornConstants.FACTION_HP_ID, 0L, LocalDate.of(2026, 8, 15));

        assertEquals(1, query.getFactionOcExclusions().size());
        FactionOcExclusion rule = query.getFactionOcExclusions().getFirst();
        assertEquals(TornConstants.FACTION_HP_ID, rule.getFactionId());
        assertNull(rule.getEffectiveFrom());
        assertTrue(rule.getOcList().contains(TornConstants.OC_NAME_BREAK_THE_BANK));
    }

    @Test
    @DisplayName("非大锅饭帮派不应用任何排除规则")
    void normalFaction_emptyExclusions() {
        OcBenefitRankingQuery query = new OcBenefitRankingQuery(9999L, 0L, LocalDate.of(2026, 8, 15));

        assertTrue(query.getFactionOcExclusions().isEmpty());
        assertTrue(query.isIncludeNormalBenefit());
        assertFalse(query.isIncludeReassignBenefit());
    }

    @Test
    @DisplayName("总榜包含所有大锅饭帮派的排除规则")
    void smthTotal_queryFlattensAllFactionRules() {
        OcBenefitRankingQuery query = new OcBenefitRankingQuery(0L, 123L, LocalDate.of(2026, 8, 15));

        assertEquals(0L, query.getFactionId());
        assertTrue(query.getFactionOcExclusions().size() >= TornConstants.REASSIGN_OC_FACTION.size());
        // 所有大锅饭帮派都出现在规则中
        for (Long fid : TornConstants.REASSIGN_OC_FACTION) {
            assertTrue(query.getFactionOcExclusions().stream()
                    .anyMatch(rule -> fid.equals(rule.getFactionId())));
        }
    }

    @Test
    @DisplayName("个人明细构造器保留时间范围并应用所属帮派规则")
    void personalBenefit_queryKeepsDateRange() {
        LocalDateTime from = LocalDateTime.of(2026, 7, 1, 0, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 7, 31, 23, 59, 59);
        OcBenefitRankingQuery query = new OcBenefitRankingQuery(TornConstants.FACTION_NOV_ID, 2001L, from, to);

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
