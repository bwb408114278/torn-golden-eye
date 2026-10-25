package pn.torn.goldeneye.torn.service.faction.oc.recommend.rank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendOutcomeEnum;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldEstimate;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldSourceEnum;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcRecommendRanker.OcRankContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 推荐评分排序器测试：覆盖开动紧迫度、单位人天产出、非负评分、稀缺度不参与评分与确定性决胜。
 *
 * @author Bai
 * @version 1.9.4
 * @since 2026.10.25
 */
@DisplayName("推荐评分排序器测试")
class OcRecommendRankerTest {
    private static final long FACTION_ID = 2095L;
    private static final long USER_ID = 100L;

    private final OcRecommendRanker ranker = new OcRecommendRanker(new OcScarcityEstimator());
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 23, 12, 0);

    @Test
    @DisplayName("停转队排在同等产出的新队之前")
    void pausedOc_shouldRankBeforeNewTeam() {
        OcRecommendCandidate paused = candidate(1L, 11L, "OC1", 8, "A#1", 300_000_000L, 6,
                now.minusHours(1));
        OcRecommendCandidate newTeam = candidate(2L, 21L, "OC2", 8, "A#1", 300_000_000L, 6, null);
        OcRankContext context = context(List.of(), Map.of(1L, 1, 2L, 1));

        List<OcRecommendCandidate> ranked = ranker.rank(List.of(newTeam, paused), null, context);

        assertThat(ranked).extracting(row -> row.oc().getId()).containsExactly(1L, 2L);
        assertThat(ranked.getFirst().score().startUrgency()).isEqualByComparingTo(new BigDecimal("1.0000"));
        assertThat(ranked.getLast().score().startUrgency()).isEqualByComparingTo(new BigDecimal("0.9500"));
    }

    @Test
    @DisplayName("还要等55小时的队排在3小时后开动的队之后")
    void farFutureOc_shouldRankAfterSoonOc() {
        OcRecommendCandidate soon = candidate(1L, 11L, "OC1", 7, "A#1", 300_000_000L, 6,
                now.plusHours(3));
        OcRecommendCandidate farFuture = candidate(2L, 21L, "OC2", 7, "A#1", 300_000_000L, 6,
                now.plusHours(55));
        OcRankContext context = context(List.of(), Map.of(1L, 1, 2L, 1));

        List<OcRecommendCandidate> ranked = ranker.rank(List.of(farFuture, soon), null, context);

        assertThat(ranked).extracting(row -> row.oc().getId()).containsExactly(1L, 2L);
        // 需求：3小时(95分)远高于55小时(31分)，时间维度不能被抹平
        assertThat(ranked.getFirst().score().startUrgency()).isEqualByComparingTo(new BigDecimal("0.9500"));
        assertThat(ranked.getLast().score().startUrgency()).isEqualByComparingTo(new BigDecimal("0.3100"));
        assertThat(ranked.getLast().score().score()).isEqualByComparingTo(new BigDecimal("2583333.33"));
    }

    @Test
    @DisplayName("同一时间档下高等级高产出队排在低等级队之前")
    void highRankOc_shouldRankBeforeLowRankOc() {
        OcRecommendCandidate highRank = candidate(1L, 11L, "Break the Bank", 8, "A#1", 300_000_000L, 6,
                now.plusHours(3));
        OcRecommendCandidate lowRank = candidate(2L, 21L, "Snow Blind", 4, "A#1", 8_925_000L, 1,
                now.plusHours(3));
        OcRankContext context = context(List.of(), Map.of(1L, 1, 2L, 1));

        List<OcRecommendCandidate> ranked = ranker.rank(List.of(lowRank, highRank), null, context);

        assertThat(ranked).extracting(row -> row.oc().getId()).containsExactly(1L, 2L);
        assertThat(ranked.getLast().score().unitMemberDayValue())
                .isLessThan(ranked.getFirst().score().unitMemberDayValue());
    }

    @Test
    @DisplayName("替代概率不再削减收益：可被替代与无样本岗位同分")
    void substituteProbability_shouldNotReduceScore() {
        OcRecommendCandidate substitutable = candidate(1L, 11L, "OC1", 7, "A#1", 300_000_000L, 6,
                now.plusHours(3));
        OcRecommendCandidate unknown = candidate(2L, 21L, "OC2", 7, "A#1", 300_000_000L, 6,
                now.plusHours(3));
        OcRankContext context = context(List.of(
                        matrix("OC1", 7, "A#1", 90, 1L),
                        matrix("OC1", 7, "A#1", 88, 2L)),
                Map.of(1L, 1, 2L, 1));

        List<OcRecommendCandidate> ranked = ranker.rank(List.of(unknown, substitutable), null, context);

        assertThat(ranked).hasSize(2);
        assertThat(ranked.getFirst().score().substituteProbability()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(ranked.getLast().score().substituteProbability()).isNull();
        // 历史缺陷：概率为1时第一项被归零。新口径下两者评分必须完全一致
        assertThat(ranked.getFirst().score().score())
                .isEqualByComparingTo(ranked.getLast().score().score());
        assertThat(ranked.getFirst().score().outcome()).isEqualTo(OcRecommendOutcomeEnum.SUBSTITUTABLE);
        assertThat(ranked.getLast().score().outcome()).isEqualTo(OcRecommendOutcomeEnum.UNKNOWN);
    }

    @Test
    @DisplayName("评分恒非负，零产出候选也不会被丢弃")
    void zeroValueCandidate_shouldStayNonNegative() {
        OcRecommendCandidate zeroValue = candidate(1L, 11L, "OC1", 4, "A#1", 0L, 1, now.plusHours(3));
        OcRankContext context = context(List.of(), Map.of(1L, 1));

        List<OcRecommendCandidate> ranked = ranker.rank(List.of(zeroValue), null, context);

        assertThat(ranked).hasSize(1);
        assertThat(ranked.getFirst().score().score()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ranked.getFirst().score().score().signum()).isNotNegative();
    }

    @Test
    @DisplayName("同分按ocId升序确定性决胜")
    void equalScore_shouldTieBreakByOcId() {
        OcRecommendCandidate second = candidate(2L, 5L, "OC2", 8, "A#1", 300_000_000L, 6, now.plusHours(3));
        OcRecommendCandidate first = candidate(1L, 9L, "OC1", 8, "A#1", 300_000_000L, 6, now.plusHours(3));
        OcRankContext context = context(List.of(), Map.of(1L, 1, 2L, 1));

        List<OcRecommendCandidate> ranked = ranker.rank(List.of(second, first), null, context);

        assertThat(ranked).extracting(row -> row.oc().getId()).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("单位人天产出相同时期望人天更短的候选排前")
    void shorterMemberDays_shouldRankFirst() {
        OcRecommendCandidate shortTeam = candidate(1L, 11L, "OC1", 8, "A#1", 100_000_000L, 2,
                now.plusHours(3));
        OcRecommendCandidate longTeam = candidate(2L, 21L, "OC2", 8, "A#1", 300_000_000L, 6,
                now.plusHours(3));
        OcRankContext context = context(List.of(), Map.of(1L, 1, 2L, 1));

        List<OcRecommendCandidate> ranked = ranker.rank(List.of(longTeam, shortTeam), null, context);

        assertThat(ranked.getFirst().score().unitMemberDayValue())
                .isEqualByComparingTo(ranked.getLast().score().unitMemberDayValue());
        assertThat(ranked).extracting(row -> row.oc().getId()).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("同分时剩余空位数少的队排前")
    void fewerEmptySlots_shouldRankFirst() {
        OcRecommendCandidate oneSlot = candidate(1L, 11L, "OC1", 8, "A#1", 300_000_000L, 6, now.plusHours(3));
        OcRecommendCandidate manySlots = candidate(2L, 21L, "OC2", 8, "A#1", 300_000_000L, 6, now.plusHours(3));
        OcRankContext context = context(List.of(), Map.of(1L, 1, 2L, 3));

        List<OcRecommendCandidate> ranked = ranker.rank(List.of(manySlots, oneSlot), null, context);

        assertThat(ranked).extracting(row -> row.oc().getId()).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("同一OC的多个空位各自成行，不做OC级去重")
    void sameOcMultipleSlots_shouldStaySeparated() {
        OcRecommendCandidate firstSlot = candidate(7L, 71L, "OC7", 8, "A#1", 300_000_000L, 6, now.plusHours(3));
        OcRecommendCandidate secondSlot = candidate(7L, 72L, "OC7", 8, "B#1", 300_000_000L, 6, now.plusHours(3));
        OcRankContext context = context(List.of(), Map.of(7L, 2));

        List<OcRecommendCandidate> ranked = ranker.rank(List.of(secondSlot, firstSlot), null, context);

        assertThat(ranked).hasSize(2);
        assertThat(ranked).extracting(row -> row.slot().getId()).containsExactly(71L, 72L);
    }

    @Test
    @DisplayName("已加入队伍时只保留严格优于当前岗位的候选，同队换岗保留")
    void baselineFilter_shouldKeepBetterAndSameOc() {
        OcRecommendCandidate worse = candidate(1L, 11L, "OC1", 8, "A#1", 100_000_000L, 6, now.plusHours(3));
        OcRecommendCandidate better = candidate(2L, 21L, "OC2", 8, "A#1", 400_000_000L, 6, now.plusHours(3));
        OcRecommendCandidate baseline = candidate(3L, 31L, "OC3", 8, "A#1", 300_000_000L, 6, now.plusHours(3));
        OcRecommendCandidate sameOc = candidate(3L, 32L, "OC3", 8, "B#1", 300_000_000L, 6, now.plusHours(3));
        OcRankContext context = context(List.of(), Map.of(1L, 1, 2L, 1, 3L, 2));

        List<OcRecommendCandidate> ranked = ranker.rank(List.of(worse, better, sameOc), baseline, context);

        // 21为更高评分的其他队，32为同队换岗：基线岗位本身不在候选里，因此不会出现31
        assertThat(ranked).extracting(row -> row.slot().getId()).containsExactly(21L, 32L);
    }

    private OcRecommendCandidate candidate(long ocId, long slotId, String ocName, int rank, String position,
                                           long expectedNetReward, int members, LocalDateTime readyTime) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(ocId);
        oc.setFactionId(FACTION_ID);
        oc.setName(ocName);
        oc.setRank(rank);
        oc.setReadyTime(readyTime);
        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setId(slotId);
        slot.setOcId(ocId);
        slot.setPosition(position);
        TornSettingOcSlotDO requirement = new TornSettingOcSlotDO();
        requirement.setOcName(ocName);
        requirement.setRank(rank);
        requirement.setSlotCode(position);
        requirement.setSlotShortCode(position);
        requirement.setPassRate(60);
        OcYieldEstimate estimate = new OcYieldEstimate(BigDecimal.valueOf(expectedNetReward),
                new BigDecimal("6.0").multiply(BigDecimal.valueOf(members)), OcYieldSourceEnum.MAPPED);
        return new OcRecommendCandidate(oc, slot, requirement, 80, estimate, null);
    }

    private TornFactionOcUserDO matrix(String ocName, int rank, String position, int passRate, long userId) {
        TornFactionOcUserDO row = new TornFactionOcUserDO();
        row.setFactionId(FACTION_ID);
        row.setUserId(userId);
        row.setOcName(ocName);
        row.setRank(rank);
        row.setPosition(position);
        row.setPassRate(passRate);
        return row;
    }

    private OcRankContext context(List<TornFactionOcUserDO> matrix, Map<Long, Integer> emptySlotsByOc) {
        return new OcRankContext(matrix, emptySlotsByOc, USER_ID, now);
    }

    @Nested
    @DisplayName("百分制评分换算")
    class Rating {
        @Test
        @DisplayName("评分恒在1至100之间，且对货币量单调不减")
        void rating_shouldStayInRangeAndBeMonotonic() {
            assertThat(OcRecommendRating.ratingOf(null)).isEqualTo(1);
            assertThat(OcRecommendRating.ratingOf(BigDecimal.ZERO)).isEqualTo(1);
            assertThat(OcRecommendRating.ratingOf(new BigDecimal("-1000000"))).isEqualTo(1);
            assertThat(OcRecommendRating.ratingOf(new BigDecimal("12000000"))).isEqualTo(100);
            assertThat(OcRecommendRating.ratingOf(new BigDecimal("90000000"))).isEqualTo(100);

            int previous = 0;
            for (long value = 100_000L; value <= 20_000_000L; value += 250_000L) {
                int rating = OcRecommendRating.ratingOf(BigDecimal.valueOf(value));
                assertThat(rating).isBetween(1, 100).isGreaterThanOrEqualTo(previous);
                previous = rating;
            }
        }

        @Test
        @DisplayName("主力队落在可区分的分数段，既不饱和也不贴底")
        void rating_shouldSpreadWorkhorseTeams() {
            // 3.53M/人天：7级 Blast from the Past 在3小时紧迫度下的评分
            assertThat(OcRecommendRating.ratingOf(new BigDecimal("3529298"))).isBetween(60, 70);
            // 8.20M/人天：8级 Break the Bank 在18小时紧迫度下的评分
            assertThat(OcRecommendRating.ratingOf(new BigDecimal("8204482"))).isBetween(80, 90);
            // 0.50M/人天：修正人天口径后的4级队
            assertThat(OcRecommendRating.ratingOf(new BigDecimal("500000"))).isLessThan(30);
        }
    }
}
