package pn.torn.goldeneye.torn.service.faction.oc.recommend.rank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendOutcomeEnum;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldEstimate;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldSourceEnum;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcOpportunityCostRanker.OcRankContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 机会成本排序器测试：覆盖稀缺度、被锁死人天、未知替代概率与确定性决胜。
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.23
 */
@DisplayName("机会成本排序器测试")
class OcOpportunityCostRankerTest {
    private static final long FACTION_ID = 2095L;
    private static final long USER_ID = 100L;
    private static final BigDecimal OCCUPANCY_COST = new BigDecimal("4.5")
            .multiply(new BigDecimal("5000000"));

    private final OcOpportunityCostRanker ranker =
            new OcOpportunityCostRanker(new OcScarcityEstimator());
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 23, 12, 0);

    @Test
    @DisplayName("除我之外无人可填的岗位排最前")
    void irreplaceableSlot_shouldRankFirst() {
        OcRecommendCandidate irreplaceable = candidate(1L, 11L, "HARD", "A#1", 300_000_000L, 6);
        OcRecommendCandidate substitutable = candidate(2L, 21L, "EASY", "A#1", 300_000_000L, 6);
        OcRankContext context = context(List.of(
                        matrix("HARD", "A#1", 90, USER_ID),
                        matrix("EASY", "A#1", 90, 1L),
                        matrix("EASY", "A#1", 88, 2L),
                        matrix("EASY", "A#1", 70, 3L)),
                List.of(), Map.of(1L, 1, 2L, 1));

        List<OcRecommendCandidate> ranked = ranker.rank(
                List.of(substitutable, irreplaceable), null, context);

        assertThat(ranked).extracting(row -> row.oc().getId()).containsExactly(1L, 2L);
        assertThat(ranked.getFirst().cost().substituteCount()).isZero();
        assertThat(ranked.getFirst().cost().opportunityCost())
                .isEqualByComparingTo(new BigDecimal("300000000").subtract(OCCUPANCY_COST));
        assertThat(ranked.getLast().cost().opportunityCost())
                .isEqualByComparingTo(OCCUPANCY_COST.negate());
    }

    @Test
    @DisplayName("有进度成员被锁死的停转队因锁死人天排前")
    void pausedOcWithLockedMembers_shouldRankFirst() {
        OcRecommendCandidate paused = candidate(1L, 11L, "PAUSED", "A#1", 300_000_000L, 6);
        OcRecommendCandidate newTeam = candidate(2L, 21L, "NEW", "A#1", 300_000_000L, 6);
        TornFactionOcSlotDO lockedSlot = occupiedSlot(1L, 999L, BigDecimal.ONE, now.minusDays(3));
        TornFactionOcSlotDO idlingSlot = occupiedSlot(2L, 998L, BigDecimal.ZERO, now.minusDays(9));
        OcRankContext context = context(List.of(), List.of(lockedSlot, idlingSlot),
                Map.of(1L, 1, 2L, 1));

        List<OcRecommendCandidate> ranked = ranker.rank(
                List.of(newTeam, paused), null, context);

        assertThat(ranked).extracting(row -> row.oc().getId()).containsExactly(1L, 2L);
        assertThat(ranked.getFirst().cost().lockedMemberDays()).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(ranked.getLast().cost().lockedMemberDays()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("被锁死人天只统计同队内progress大于0的成员")
    void lockedMemberDays_shouldIgnoreZeroProgressMemberOfSameOc() {
        OcRecommendCandidate locked = candidate(5L, 51L, "OC5", "A#1", 100_000_000L, 4);
        OcRecommendCandidate plain = candidate(6L, 61L, "OC6", "A#1", 100_000_000L, 4);
        TornFactionOcSlotDO lockedSlot = occupiedSlot(5L, 901L, BigDecimal.ONE, now.minusDays(2));
        TornFactionOcSlotDO idlingSlot = occupiedSlot(5L, 902L, BigDecimal.ZERO, now.minusDays(9));
        OcRankContext context = context(List.of(), List.of(lockedSlot, idlingSlot),
                Map.of(5L, 1, 6L, 1));

        List<OcRecommendCandidate> ranked = ranker.rank(List.of(plain, locked), null, context);

        assertThat(ranked.getFirst().oc().getId()).isEqualTo(5L);
        // 若把progress=0的空转成员也算进来会是11天，实际只算有进度的2天
        assertThat(ranked.getFirst().cost().lockedMemberDays()).isEqualByComparingTo(new BigDecimal("2"));
        assertThat(ranked.getLast().cost().lockedMemberDays()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("替代概率未知时按中性计入且不高于确认无人可替代")
    void unknownSubstituteProbability_shouldBeNeutral() {
        OcRecommendCandidate known = candidate(1L, 11L, "KNOWN", "Known#1", 200_000_000L, 4);
        OcRecommendCandidate unknown = candidate(2L, 21L, "UNKNOWN", "Unknown#1", 200_000_000L, 4);
        OcRankContext context = context(List.of(matrix("KNOWN", "Known#1", 95, USER_ID)),
                List.of(), Map.of(1L, 1, 2L, 1));

        List<OcRecommendCandidate> ranked = ranker.rank(List.of(unknown, known), null, context);

        assertThat(ranked.getFirst().cost().substituteProbability()).isNotNull();
        assertThat(ranked.getLast().cost().substituteProbability()).isNull();
        assertThat(ranked.getLast().cost().opportunityCost())
                .isEqualByComparingTo(ranked.getFirst().cost().opportunityCost());
        assertThat(ranked.getFirst().cost().outcome()).isEqualTo(OcRecommendOutcomeEnum.IRREPLACEABLE);
        assertThat(ranked.getLast().cost().outcome()).isEqualTo(OcRecommendOutcomeEnum.UNKNOWN);
        assertThat(ranked).extracting(row -> row.oc().getId()).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("同分按ocId升序确定性决胜")
    void equalCost_shouldTieBreakByOcId() {
        OcRecommendCandidate second = candidate(2L, 5L, "OC2", "A#1", 100_000_000L, 4);
        OcRecommendCandidate first = candidate(1L, 9L, "OC1", "A#1", 100_000_000L, 4);
        OcRankContext context = context(List.of(), List.of(), Map.of(1L, 1, 2L, 1));

        List<OcRecommendCandidate> ranked = ranker.rank(List.of(second, first), null, context);

        assertThat(ranked).extracting(row -> row.oc().getId()).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("同一OC的多个空位各自成行并按替代概率从低到高排列")
    void sameOcMultipleSlots_shouldStaySeparated() {
        OcRecommendCandidate hardSlot = candidate(7L, 71L, "OC7", "Hard#1", 300_000_000L, 5);
        OcRecommendCandidate easySlot = candidate(7L, 72L, "OC7", "Easy#1", 300_000_000L, 5);
        OcRankContext context = context(List.of(
                        matrix("OC7", "Hard#1", 95, USER_ID),
                        matrix("OC7", "Easy#1", 70, 1L),
                        matrix("OC7", "Easy#1", 71, 2L),
                        matrix("OC7", "Easy#1", 72, 3L)),
                List.of(), Map.of(7L, 2));

        List<OcRecommendCandidate> ranked = ranker.rank(List.of(easySlot, hardSlot), null, context);

        assertThat(ranked).hasSize(2);
        assertThat(ranked).extracting(row -> row.slot().getId()).containsExactly(71L, 72L);
        assertThat(ranked.getFirst().cost().substituteCount()).isZero();
        // 同队两行的替代概率不同，机会成本因此形成梯度：更难被替代的岗位排在前
        assertThat(ranked.getFirst().cost().opportunityCost())
                .isGreaterThan(ranked.getLast().cost().opportunityCost());
    }

    @Test
    @DisplayName("已加入队伍时只保留严格优于当前岗位的候选")
    void baselineFilter_shouldKeepOnlyStrictlyBetter() {
        OcRecommendCandidate worse = candidate(1L, 11L, "OC1", "A#1", 100_000_000L, 4);
        OcRecommendCandidate better = candidate(2L, 21L, "OC2", "A#1", 400_000_000L, 4);
        OcRecommendCandidate baseline = candidate(3L, 31L, "OC3", "A#1", 200_000_000L, 4);
        OcRankContext context = context(List.of(), List.of(), Map.of(1L, 1, 2L, 1, 3L, 1));

        List<OcRecommendCandidate> ranked = ranker.rank(
                List.of(worse, better), baseline, context);

        assertThat(ranked).extracting(row -> row.oc().getId()).containsExactly(2L);
    }

    @Test
    @DisplayName("期望人天更短的候选在机会成本相同时排前")
    void shorterMemberDays_shouldRankFirst() {
        OcRecommendCandidate shortTeam = candidate(1L, 11L, "OC1", "A#1", 100_000_000L, 2);
        OcRecommendCandidate longTeam = candidate(2L, 21L, "OC2", "A#1", 100_000_000L, 6);
        OcRankContext context = context(List.of(), List.of(), Map.of(1L, 1, 2L, 1));

        List<OcRecommendCandidate> ranked = ranker.rank(List.of(longTeam, shortTeam), null, context);

        assertThat(ranked).extracting(row -> row.oc().getId()).containsExactly(1L, 2L);
    }

    private OcRecommendCandidate candidate(long ocId, long slotId, String ocName, String position,
                                           long expectedNetReward, int members) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(ocId);
        oc.setFactionId(FACTION_ID);
        oc.setName(ocName);
        oc.setRank(8);
        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setId(slotId);
        slot.setOcId(ocId);
        slot.setPosition(position);
        TornSettingOcSlotDO requirement = new TornSettingOcSlotDO();
        requirement.setOcName(ocName);
        requirement.setRank(8);
        requirement.setSlotCode(position);
        requirement.setSlotShortCode(position);
        requirement.setPassRate(60);
        OcYieldEstimate estimate = new OcYieldEstimate(BigDecimal.valueOf(expectedNetReward),
                new BigDecimal("4.5").multiply(BigDecimal.valueOf(members)), OcYieldSourceEnum.MAPPED);
        return new OcRecommendCandidate(oc, slot, requirement, 80, estimate, null);
    }

    private TornFactionOcUserDO matrix(String ocName, String position, int passRate, long userId) {
        TornFactionOcUserDO row = new TornFactionOcUserDO();
        row.setFactionId(FACTION_ID);
        row.setUserId(userId);
        row.setOcName(ocName);
        row.setRank(8);
        row.setPosition(position);
        row.setPassRate(passRate);
        return row;
    }

    private TornFactionOcSlotDO occupiedSlot(long ocId, long userId, BigDecimal progress,
                                             LocalDateTime joinTime) {
        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setOcId(ocId);
        slot.setUserId(userId);
        slot.setProgress(progress);
        slot.setJoinTime(joinTime);
        return slot;
    }

    private OcRankContext context(List<TornFactionOcUserDO> matrix, List<TornFactionOcSlotDO> occupiedSlots,
                                  Map<Long, Integer> emptySlotsByOc) {
        return new OcRankContext(matrix, occupiedSlots, emptySlotsByOc, USER_ID, now);
    }
}
