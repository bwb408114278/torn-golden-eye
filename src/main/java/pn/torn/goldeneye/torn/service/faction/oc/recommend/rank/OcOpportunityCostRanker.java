package pn.torn.goldeneye.torn.service.faction.oc.recommend.rank;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldEstimate;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldSourceEnum;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 机会成本排序器。
 *
 * <p>排序键只有一个：机会成本降序。之后依次按单位人天产出降序、期望人天升序、
 * 剩余空位数升序做确定性决胜，最后按ocId、slotId升序兜底，保证同一快照下结果可复现。
 * 同一OC的多个空位各自成行，不做OC级去重：抢位失败后同队的第二个岗位仍值得加入。
 * 本类不写库、不读系统时间（时间由调用方传入），可脱离Spring单测。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.23
 */
@Component
@RequiredArgsConstructor
public class OcOpportunityCostRanker {
    /**
     * 机会成本的小数位数。
     */
    private static final int COST_SCALE = 2;
    /**
     * 一天的秒数，用于把等待秒数折算为人天。
     */
    private static final BigDecimal SECONDS_PER_DAY = BigDecimal.valueOf(86400L);
    /**
     * 人天折算的小数位数。
     */
    private static final int MEMBER_DAY_SCALE = 4;

    private final OcScarcityEstimator scarcityEstimator;

    /**
     * 计算单个候选的机会成本。
     *
     * @param candidate 候选，estimate为空时按0产出处理
     * @param context   排序上下文，提供岗位索引、已占用岗位、空位统计与时间基准
     * @return 机会成本快照
     */
    public OcOpportunityCost evaluate(OcRecommendCandidate candidate, OcRankContext context) {
        OcYieldEstimate estimate = candidate.estimate() == null
                ? OcYieldEstimate.zero(OcYieldSourceEnum.MAPPED) : candidate.estimate();
        int demand = Math.max(1, emptySlots(context, candidate));
        OcScarcityEstimator.OcSubstituteResult substitute = scarcityEstimator.estimateFromIndex(
                candidate.requirement(), context.slotIndex(), context.userId(), demand);

        BigDecimal substituteProbability = substitute == null ? null : substitute.probability();
        BigDecimal expectedGain = substituteProbability == null
                ? estimate.expectedNetReward()
                : estimate.expectedNetReward().multiply(BigDecimal.ONE.subtract(substituteProbability));

        BigDecimal lockedMemberDays = lockedMemberDays(candidate, context);
        BigDecimal lockedCost = lockedMemberDays.multiply(OcRecommendTunings.DEFAULT_DAILY_WAIT_COST);
        BigDecimal occupiedMemberDays = OcRecommendTunings.MEMBER_DAYS_PER_SLOT;
        BigDecimal occupancyCost = occupiedMemberDays
                .multiply(OcRecommendTunings.DEFAULT_DAILY_WAIT_COST)
                .multiply(OcRecommendTunings.OCCUPANCY_COST_FACTOR);

        BigDecimal opportunityCost = expectedGain.add(lockedCost).subtract(occupancyCost)
                .setScale(COST_SCALE, RoundingMode.HALF_UP);
        return new OcOpportunityCost(estimate.expectedNetReward(), substituteProbability,
                substitute == null ? null : substitute.substituteCount(),
                lockedMemberDays, occupiedMemberDays, opportunityCost);
    }

    /**
     * 计算机会成本并排序，已加入队伍时只保留严格优于当前岗位的候选。
     *
     * @param candidates 候选列表
     * @param baseline   当前岗位 baseline，null表示不做基线过滤
     * @param context    排序上下文
     * @return 已按机会成本排序且通过基线过滤的候选列表
     */
    public List<OcRecommendCandidate> rank(List<OcRecommendCandidate> candidates,
                                           OcRecommendCandidate baseline,
                                           OcRankContext context) {
        List<OcRecommendCandidate> costed = candidates.stream()
                .map(candidate -> candidate.withCost(evaluate(candidate, context)))
                .toList();
        OcRecommendCandidate costedBaseline = baseline == null
                ? null : baseline.withCost(evaluate(baseline, context));
        return costed.stream()
                .filter(candidate -> costedBaseline == null
                        || compareEconomy(candidate, costedBaseline, context) < 0)
                .sorted(buildComparator(context))
                .toList();
    }

    /**
     * 构建完整排序比较器：机会成本降序、单位人天产出降序、期望人天升序、
     * 剩余空位数升序、ocId与slotId升序。
     *
     * @param context 排序上下文
     * @return 候选比较器
     */
    public Comparator<OcRecommendCandidate> buildComparator(OcRankContext context) {
        return Comparator
                .comparing((OcRecommendCandidate candidate) -> candidate.cost().opportunityCost(),
                        Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.estimate().netPerMemberDay(),
                        Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.estimate().expectedMemberDays())
                .thenComparing(candidate -> emptySlots(context, candidate))
                .thenComparing(candidate -> candidate.oc().getId(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(candidate -> candidate.slot().getId(),
                        Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /**
     * 按经济性比较两个候选，比较顺序与完整排序键的前四项一致，不比较兜底的ID，
     * 避免同分岗位因ID大小被判为「更优」而反复切换。
     *
     * @param left    左候选
     * @param right   右候选
     * @param context 排序上下文
     * @return 左候选排序在前时返回负数
     */
    private int compareEconomy(OcRecommendCandidate left, OcRecommendCandidate right,
                               OcRankContext context) {
        int byCost = right.cost().opportunityCost().compareTo(left.cost().opportunityCost());
        if (byCost != 0) {
            return byCost;
        }
        int byUnitValue = right.estimate().netPerMemberDay().compareTo(left.estimate().netPerMemberDay());
        if (byUnitValue != 0) {
            return byUnitValue;
        }
        int byMemberDays = left.estimate().expectedMemberDays()
                .compareTo(right.estimate().expectedMemberDays());
        if (byMemberDays != 0) {
            return byMemberDays;
        }
        return Integer.compare(emptySlots(context, left), emptySlots(context, right));
    }

    /**
     * 计算候选所属OC的剩余空位数。
     *
     * @param context   排序上下文
     * @param candidate 候选
     * @return 剩余空位数；统计缺失时为0
     */
    private int emptySlots(OcRankContext context, OcRecommendCandidate candidate) {
        if (context.emptySlotsByOc() == null || candidate.oc() == null) {
            return 0;
        }
        return context.emptySlotsByOc().getOrDefault(candidate.oc().getId(), 0);
    }

    /**
     * 计算停转队中已进入进度的成员被锁死的人天，只统计progress大于0的成员。
     *
     * @param candidate 候选
     * @param context   排序上下文
     * @return 被锁死人天，非负
     */
    private BigDecimal lockedMemberDays(OcRecommendCandidate candidate, OcRankContext context) {
        if (context.occupiedSlots() == null || candidate.oc() == null
                || candidate.oc().getId() == null || context.now() == null) {
            return BigDecimal.ZERO;
        }
        Long ocId = candidate.oc().getId();
        BigDecimal total = BigDecimal.ZERO;
        for (TornFactionOcSlotDO slot : context.occupiedSlots()) {
            if (!ocId.equals(slot.getOcId()) || slot.getJoinTime() == null || slot.getProgress() == null
                    || slot.getProgress().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            long seconds = Math.max(0L, Duration.between(slot.getJoinTime(), context.now()).getSeconds());
            total = total.add(BigDecimal.valueOf(seconds)
                    .divide(SECONDS_PER_DAY, MEMBER_DAY_SCALE, RoundingMode.HALF_UP));
        }
        return total;
    }

    /**
     * 排序上下文：一次推荐请求内共享的只读事实，避免为每个候选单独查库。
     *
     * @param factionOcData  全帮派成功率矩阵（含全局默认行）
     * @param occupiedSlots  招募中OC的已占用岗位，用于计算被锁死人天
     * @param emptySlotsByOc 每个OC的剩余空位数，作为第四决胜键
     * @param userId         被推荐的成员ID
     * @param now            本次推荐的时间基准
     * @param slotIndex      按（OC名称, 等级, 岗位短编码）预分组的矩阵索引，随上下文只建一次
     * @author Bai
     * @version 1.9.3
     * @since 2026.10.23
     */
    public record OcRankContext(List<TornFactionOcUserDO> factionOcData,
                                List<TornFactionOcSlotDO> occupiedSlots,
                                Map<Long, Integer> emptySlotsByOc,
                                Long userId,
                                LocalDateTime now,
                                Map<String, List<TornFactionOcUserDO>> slotIndex) {

        /**
         * 归一化空值，避免排序时做空判断。
         */
        public OcRankContext {
            factionOcData = factionOcData == null ? List.of() : List.copyOf(factionOcData);
            occupiedSlots = occupiedSlots == null ? List.of() : List.copyOf(occupiedSlots);
            emptySlotsByOc = emptySlotsByOc == null ? Map.of() : Map.copyOf(emptySlotsByOc);
            slotIndex = slotIndex == null ? Map.of() : Map.copyOf(slotIndex);
        }

        /**
         * 由原始只读事实构造上下文并顺手建好岗位索引：一次推荐或一批分配只建这一份，
         * 之后每个候选的替代概率查询都是O(1)。
         */
        public OcRankContext(List<TornFactionOcUserDO> factionOcData,
                             List<TornFactionOcSlotDO> occupiedSlots,
                             Map<Long, Integer> emptySlotsByOc,
                             Long userId,
                             LocalDateTime now) {
            this(factionOcData, occupiedSlots, emptySlotsByOc, userId, now,
                    OcScarcityEstimator.indexBySlot(factionOcData));
        }

        /**
         * 复制上下文并替换被推荐成员，用于批量分配时逐人复用同一份只读事实与岗位索引。
         *
         * @param newUserId 新的成员ID
         * @return 替换成员后的上下文
         */
        public OcRankContext withUserId(Long newUserId) {
            return new OcRankContext(factionOcData, occupiedSlots, emptySlotsByOc,
                    newUserId, now, slotIndex);
        }
    }
}
