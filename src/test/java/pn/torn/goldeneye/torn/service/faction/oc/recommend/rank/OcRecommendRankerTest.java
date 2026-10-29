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
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 推荐评分排序器测试：覆盖开动紧迫度、单位人天产出、非负评分、稀缺度不参与评分与确定性决胜。
 *
 * @author Bai
 * @version 1.9.6
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
        OcRecommendCandidate worse = candidate(1L, 11L, "OC1", 8, "A#1", 100_000_000L, 6, now.plusHours(4));
        OcRecommendCandidate better = candidate(2L, 21L, "OC2", 8, "A#1", 400_000_000L, 6, now.plusHours(2));
        OcRecommendCandidate baseline = candidate(3L, 31L, "OC3", 8, "A#1", 300_000_000L, 6, now.plusHours(3));
        OcRecommendCandidate sameOc = candidate(3L, 32L, "OC3", 8, "B#1", 300_000_000L, 6, now.plusHours(3));
        OcRankContext context = context(List.of(), Map.of(1L, 1, 2L, 1, 3L, 2));

        List<OcRecommendCandidate> ranked = ranker.rank(List.of(worse, better, sameOc), baseline, context);

        // 21为更高评分的其他队，32为同队换岗：基线岗位本身不在候选里，因此不会出现31
        assertThat(ranked).extracting(row -> row.slot().getId()).containsExactly(21L, 32L);
    }

    @Test
    @DisplayName("核心胜任等级锚定主推荐并保留负边际与高等级后置")
    void marginalAnchor_shouldGroupWithoutDroppingCandidates() {
        OcRecommendCandidate r9Public = marginal(candidate(4L, 41L, "Hostile", 10, "H#1",
                900_000_000L, 6, now.plusHours(3)), "7", 60);
        OcRecommendCandidate r8Core = marginal(candidate(1L, 11L, "BTB", 8, "T#2",
                300_000_000L, 6, now.plusHours(3)), "0", 65);
        OcRecommendCandidate r8Negative = marginal(candidate(2L, 21L, "BTB", 8, "M#1",
                300_000_000L, 6, now.plusHours(3)), "-1", 65);
        OcRecommendCandidate r7Positive = marginal(candidate(3L, 31L, "BftP", 7, "P#1",
                200_000_000L, 6, now.plusHours(3)), "5", 55);

        List<OcRecommendCandidate> ranked = ranker.rank(
                List.of(r9Public, r8Negative, r7Positive, r8Core), null,
                context(List.of(), Map.of(1L, 1, 2L, 1, 3L, 1, 4L, 1)));

        assertThat(ranked).extracting(row -> row.slot().getId()).containsExactly(11L, 31L, 21L, 41L);
        assertThat(ranked).extracting(row -> row.score().rating())
                .isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    @DisplayName("没有核心胜任时非负大众岗先于负边际核心岗")
    void noCoreAnchor_shouldFallBackToPositivePublicPosition() {
        OcRecommendCandidate negativeCore = marginal(candidate(1L, 11L, "WoO", 7, "L#2",
                200_000_000L, 5, now.plusHours(3)), "-1.5", 65);
        OcRecommendCandidate zeroPublic = marginal(candidate(2L, 21L, "BftP", 7, "P#1",
                200_000_000L, 6, now.plusHours(3)), "0", 55);

        List<OcRecommendCandidate> ranked = ranker.rank(List.of(negativeCore, zeroPublic), null,
                context(List.of(), Map.of(1L, 1, 2L, 1)));

        assertThat(ranked).extracting(row -> row.slot().getId()).containsExactly(21L, 11L);
    }

    @Test
    @DisplayName("同时间同层级跨OC与同OC都按ΔV决胜")
    void marginal_shouldBreakTieAcrossOcAndInsideSameOc() {
        OcRecommendCandidate low = marginal(candidate(1L, 11L, "BTB", 8, "T#1",
                300_000_000L, 6, now.plusHours(3)), "1", 60);
        OcRecommendCandidate high = marginal(candidate(1L, 12L, "BTB", 8, "T#2",
                300_000_000L, 6, now.plusHours(3)), "2", 60);
        OcRecommendCandidate otherOc = marginal(candidate(2L, 21L, "CP", 8, "I#1",
                300_000_000L, 6, now.plusHours(3)), "999", 60);

        List<OcRecommendCandidate> ranked = ranker.rank(List.of(low, otherOc, high), null,
                context(List.of(), Map.of(1L, 1, 2L, 1)));

        assertThat(ranked).extracting(row -> row.slot().getId()).containsExactly(21L, 12L, 11L);
    }

    @Test
    @DisplayName("DAG不可用不等于合法零边际且仍参与R6推荐")
    void missingMarginal_shouldRemainFallbackInsteadOfZero() {
        OcRecommendCandidate fallback = candidate(3L, 31L, "Ship", 9, "S#1",
                300_000_000L, 5, now.plusHours(1));
        OcRecommendCandidate zeroCore = marginal(candidate(1L, 11L, "BTB", 8, "T#2",
                300_000_000L, 6, now.plusHours(3)), "0", 65);
        OcRecommendCandidate negativeCore = marginal(candidate(2L, 21L, "BTB", 8, "M#1",
                300_000_000L, 6, now.plusHours(3)), "-1", 65);

        List<OcRecommendCandidate> ranked = ranker.rank(List.of(negativeCore, fallback, zeroCore), null,
                context(List.of(), Map.of(1L, 1, 2L, 1, 3L, 1)));

        assertThat(ranked).extracting(row -> row.slot().getId()).containsExactly(31L, 11L, 21L);
        assertThat(ranked.getFirst().marginalValue()).isNull();
    }

    @Test
    @DisplayName("同rank同核心先比时间再以跨OC ΔV决胜")
    void sameRankCore_shouldUseTimeThenMarginal() {
        OcRecommendCandidate btb = marginal(candidate(1L, 11L, "BTB", 8, "Muscle#1",
                266_935_000L, 6, now.plusHours(2)), "0", 65);
        OcRecommendCandidate ship = marginal(candidate(2L, 21L, "Ship", 8, "Hustler#1",
                130_671_000L, 5, now.plusHours(1)), "3520702", 65);
        assertFirst(2L, Map.of(1L, 1, 2L, 1), "Ship等1小时应先于BTB等2小时", btb, ship);

        btb.oc().setReadyTime(now.plusHours(1));
        assertFirst(2L, Map.of(1L, 1, 2L, 1), "同1小时时Ship的ΔV更高", btb, ship);

        OcRecommendCandidate stacking = marginal(candidate(3L, 31L, "StD", 8, "Imitator#1",
                239_669_000L, 4, now.plusHours(1)), "6283616", 65);
        assertFirst(3L, Map.of(1L, 1, 3L, 1), "StD当前时间相同且ΔV更高，后续链等待不扣减", btb, stacking);
    }

    @Test
    @DisplayName("同rank跨OC核心岗容差覆盖全部已确认边界")
    void coreTolerance_shouldMatchConfirmedScenarios() {
        int[][] cases = {
                {1, 1, 1}, {3, 1, 1}, {4, 1, 1}, {6, 1, 1},
                {12, 1, 2}, {24, 1, 2}, {8, 6, 1}, {14, 12, 1}};
        for (int[] scenario : cases) {
            OcRecommendCandidate core = marginal(candidate(1L, 11L, "Core", 8, "Muscle#1",
                    266_935_000L, 6, now.plusHours(scenario[0])), "0", 65);
            OcRecommendCandidate publicSlot = marginal(candidate(2L, 21L, "Public", 8, "Muscle#2",
                    266_935_000L, 6, now.plusHours(scenario[1])), "0", 60);
            assertFirst(scenario[2], Map.of(1L, 1, 2L, 1),
                    "核心" + scenario[0] + "小时/大众" + scenario[1] + "小时", core, publicSlot);
        }
    }

    @Test
    @DisplayName("同核心跨rank只有同时间由高rank优先")
    void sameCoreAcrossRank_shouldMatchConfirmedScenarios() {
        int[][] cases = {{1, 1, 1}, {3, 1, 2}, {2, 1, 2}, {4, 2, 2}};
        for (int[] scenario : cases) {
            OcRecommendCandidate rank8 = marginal(candidate(1L, 11L, "BTB", 8, "Muscle#1",
                    266_935_000L, 6, now.plusHours(scenario[0])), "0", 65);
            OcRecommendCandidate rank7 = marginal(candidate(2L, 21L, "BftP", 7, "Muscle#1",
                    114_781_000L, 6, now.plusHours(scenario[1])), "11836831", 65);
            assertFirst(scenario[2], Map.of(1L, 1, 2L, 1),
                    "8级" + scenario[0] + "小时/7级" + scenario[1] + "小时", rank8, rank7);
        }
    }

    @Test
    @DisplayName("7级核心与8级大众同层且时间早者优先")
    void crossRankAndCore_shouldUseEffectiveTierAndTime() {
        OcRecommendCandidate rank8Public = marginal(candidate(1L, 11L, "BTB", 8, "Muscle#2",
                266_935_000L, 6, now.plusHours(1)), "0", 60);
        OcRecommendCandidate rank7Core = marginal(candidate(2L, 21L, "BftP", 7, "Muscle#1",
                114_781_000L, 6, now.plusHours(1)), "11836831", 65);
        List<OcRecommendCandidate> tied = ranker.rank(List.of(rank8Public, rank7Core), null,
                context(List.of(), Map.of(1L, 1, 2L, 1)));
        assertThat(tied).extracting(row -> row.score().rating()).containsOnly(tied.getFirst().score().rating());

        rank8Public.oc().setReadyTime(now.plusHours(3));
        assertFirst(2L, Map.of(1L, 1, 2L, 1), "8级大众晚2小时，7级核心优先", rank8Public, rank7Core);

        rank8Public.oc().setReadyTime(now.plusHours(1));
        rank7Core.oc().setReadyTime(now.plusHours(3));
        assertFirst(1L, Map.of(1L, 1, 2L, 1), "8级大众早2小时，8级大众优先", rank8Public, rank7Core);
    }

    @Test
    @DisplayName("同ocId按ΔV选坑且相同边际并列")
    void sameOc_shouldUseMarginalWithoutCoreOverride() {
        OcRecommendCandidate muscle = marginal(candidate(1L, 11L, "BTB", 8, "Muscle#1",
                266_935_000L, 6, now.plusHours(1)), "0", 65);
        OcRecommendCandidate thief = marginal(candidate(1L, 12L, "BTB", 8, "Thief#1",
                267_833_000L, 6, now.plusHours(1)), "898481", 60);
        List<OcRecommendCandidate> thiefFirst = ranker.rank(List.of(muscle, thief), null,
                context(List.of(), Map.of(1L, 2)));
        assertThat(thiefFirst.getFirst().slot().getId()).as("大众Thief凭更高ΔV反超核心Muscle").isEqualTo(12L);

        OcRecommendCandidate best = marginal(candidate(2L, 21L, "BftP", 7, "Muscle#1",
                114_781_000L, 6, now.plusHours(1)), "11836831", 65);
        OcRecommendCandidate middle = marginal(candidate(2L, 22L, "BftP", 7, "Bomber#1",
                112_676_000L, 6, now.plusHours(1)), "9732475", 65);
        OcRecommendCandidate last = marginal(candidate(2L, 23L, "BftP", 7, "Engineer#1",
                111_583_000L, 6, now.plusHours(1)), "8639585", 65);
        List<OcRecommendCandidate> ranked = ranker.rank(List.of(last, best, middle), null,
                context(List.of(), Map.of(2L, 3)));
        assertThat(ranked).extracting(row -> row.slot().getId()).containsExactly(21L, 22L, 23L);

        OcRecommendCandidate equalPublic = marginal(candidate(3L, 32L, "BTB", 8, "Muscle#2",
                266_935_000L, 6, now.plusHours(1)), "0", 60);
        OcRecommendCandidate equalCore = marginal(candidate(3L, 31L, "BTB", 8, "Muscle#1",
                266_935_000L, 6, now.plusHours(1)), "0", 65);
        List<OcRecommendCandidate> equal = ranker.rank(List.of(equalPublic, equalCore), null,
                context(List.of(), Map.of(3L, 2)));
        assertThat(equal).extracting(row -> row.score().rating()).containsOnly(equal.getFirst().score().rating());
    }

    @Test
    @DisplayName("空位数只在时间相同时决胜")
    void emptySlots_shouldOnlyBreakTimeTie() {
        OcRecommendCandidate full = marginal(candidate(1L, 11L, "BTB", 8, "Muscle#1",
                266_935_000L, 6, now.plusHours(1)), "0", 65);
        OcRecommendCandidate notFull = marginal(candidate(2L, 21L, "Ship", 8, "Hustler#1",
                130_671_000L, 5, now.plusHours(1)), "0", 65);
        assertFirst(1L, Map.of(1L, 1, 2L, 3), "同时间加入即满员优先", full, notFull);

        full.oc().setReadyTime(now.plusHours(3));
        assertFirst(2L, Map.of(1L, 1, 2L, 3), "满员优势不能抵消更晚时间", full, notFull);
        full.oc().setReadyTime(now.plusHours(24));
        assertFirst(2L, Map.of(1L, 1, 2L, 3), "满员等24小时仍由早停转队优先", full, notFull);

        full.oc().setReadyTime(now.plusHours(1));
        assertFirst(1L, Map.of(1L, 2, 2L, 3), "非末位同时间剩余一人优先", full, notFull);
        full.oc().setReadyTime(now.plusHours(3));
        assertFirst(2L, Map.of(1L, 2, 2L, 3), "非末位晚停转由早停转优先", full, notFull);
        full.oc().setReadyTime(now.plusHours(1));
        notFull.oc().setReadyTime(now.plusHours(3));
        assertFirst(1L, Map.of(1L, 2, 2L, 3), "非末位早停转优先", full, notFull);
    }

    @Test
    @DisplayName("新队位于明确等待2小时与3小时之间")
    void newTeam_shouldMatchConfirmedTimeBoundary() {
        int[] existingHours = {1, 2, 3, 4, 6, 24};
        for (int hours : existingHours) {
            OcRecommendCandidate newTeam = marginal(candidate(1L, 11L, "BTB", 8, "Muscle#1",
                    266_935_000L, 6, null), "0", 65);
            OcRecommendCandidate existing = marginal(candidate(2L, 21L, "Ship", 8, "Hustler#1",
                    130_671_000L, 5, now.plusHours(hours)), "0", 65);
            long expected = hours <= 2 ? 2L : 1L;
            assertFirst(expected, Map.of(1L, 1, 2L, 1), "新队对比现有队" + hours + "小时", newTeam, existing);
        }
    }

    @Test
    @DisplayName("分钟级时间与已停转先后均按真实readyTime")
    void actualReadyTime_shouldCoverMinutesAndStoppedTeams() {
        LocalDateTime[][] cases = {
                {now.plusMinutes(70), now.plusMinutes(80)},
                {now.plusMinutes(70), now.plusMinutes(110)},
                {now.plusMinutes(115), now.plusMinutes(125)},
                {now.minusHours(5), now.minusHours(1)}};
        for (LocalDateTime[] scenario : cases) {
            OcRecommendCandidate earlier = marginal(candidate(1L, 11L, "BTB", 8, "Muscle#1",
                    266_935_000L, 6, scenario[0]), "0", 65);
            OcRecommendCandidate later = marginal(candidate(2L, 21L, "Ship", 8, "Hustler#1",
                    130_671_000L, 5, scenario[1]), "0", 65);
            assertFirst(1L, Map.of(1L, 1, 2L, 1), "真实时间早者优先", earlier, later);
        }
    }

    @Test
    @DisplayName("同名不同ocId按不同队伍比较时间与ΔV")
    void sameNameDifferentOcId_shouldNotUseSingleTeamSlotRule() {
        OcRecommendCandidate firstTeam = marginal(candidate(1L, 11L, "BTB", 8, "Muscle#1",
                266_935_000L, 6, now.plusHours(2)), "0", 65);
        OcRecommendCandidate secondTeam = marginal(candidate(2L, 21L, "BTB", 8, "Muscle#1",
                266_935_000L, 6, now.plusHours(1)), "0", 65);
        assertFirst(2L, Map.of(1L, 1, 2L, 1), "同名第二队更早停转", firstTeam, secondTeam);

        secondTeam.oc().setReadyTime(firstTeam.oc().getReadyTime());
        secondTeam = secondTeam.withMarginalValue(BigDecimal.ONE);
        assertFirst(2L, Map.of(1L, 1, 2L, 1), "同名不同队同时间仍可按ΔV比较", firstTeam, secondTeam);
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

    /**
     * 断言一组候选的首选OC ID。
     *
     * @param expectedOcId 预期首选OC ID
     * @param emptySlots   各OC剩余空位
     * @param scenario     场景说明
     * @param candidates   候选列表
     */
    private void assertFirst(long expectedOcId, Map<Long, Integer> emptySlots, String scenario,
                             OcRecommendCandidate... candidates) {
        List<OcRecommendCandidate> ranked = ranker.rank(List.of(candidates), null,
                context(List.of(), emptySlots));
        assertThat(ranked.getFirst().oc().getId()).as(scenario).isEqualTo(expectedOcId);
    }

    private OcRecommendCandidate marginal(OcRecommendCandidate candidate, String value, int threshold) {
        candidate.requirement().setPassRate(threshold);
        return candidate.withMarginalValue(new BigDecimal(value));
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
}
