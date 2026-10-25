package pn.torn.goldeneye.torn.service.faction.oc.recommend.rank;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldEstimate;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldSourceEnum;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 推荐评分与排序器。
 *
 * <p>排序键只有一个：推荐评分 = 单位人天产出 × 开动紧迫度（见{@link OcRecommendScore}），
 * 之后依次按单位人天产出降序、期望人天升序、剩余空位数升序做确定性决胜，
 * 最后按ocId、slotId升序兜底，保证同一快照下结果可复现。</p>
 *
 * <p>本类不做任何正负过滤：评分恒非负，候选只要通过可填性判定就一定进入结果，
 * 「有空位却告诉用户没有合适队伍」是明确禁止的行为。已加入队伍时只保留严格优于
 * 当前岗位的候选，但同一支队的换岗候选不参与该过滤——换岗不损失进度，也不改变评分。</p>
 *
 * <p>同一OC的多个空位各自成行，不做OC级去重：抢位失败后同队的第二个岗位仍值得加入。
 * 本类不写库、不读系统时间（时间由调用方传入），可脱离Spring单测。</p>
 *
 * @author Bai
 * @version 1.9.4
 * @since 2026.10.23
 */
@Component
@RequiredArgsConstructor
public class OcRecommendRanker {
    /**
     * 推荐评分的小数位数。
     */
    private static final int SCORE_SCALE = 2;

    private final OcScarcityEstimator scarcityEstimator;

    /**
     * 计算单个候选的推荐评分。
     *
     * @param candidate 候选，estimate为空时按0产出处理
     * @param context   排序上下文，提供岗位索引、空位统计与时间基准
     * @return 推荐评分快照
     */
    public OcRecommendScore evaluate(OcRecommendCandidate candidate, OcRankContext context) {
        OcYieldEstimate estimate = candidate.estimate() == null
                ? OcYieldEstimate.zero(OcYieldSourceEnum.MAPPED) : candidate.estimate();
        BigDecimal unitMemberDayValue = estimate.netPerMemberDay();
        BigDecimal startUrgency = OcRecommendStartUrgency.urgencyOf(candidate.oc(), context.now());

        int demand = Math.max(1, emptySlots(context, candidate));
        OcScarcityEstimator.OcSubstituteResult substitute = scarcityEstimator.estimateFromIndex(
                candidate.requirement(), context.slotIndex(), context.userId(), demand);
        BigDecimal score = unitMemberDayValue.multiply(startUrgency).setScale(SCORE_SCALE, RoundingMode.HALF_UP);
        return new OcRecommendScore(unitMemberDayValue, startUrgency, score,
                substitute == null ? null : substitute.probability(),
                substitute == null ? null : substitute.substituteCount());
    }

    /**
     * 计算评分并排序，已加入队伍时只保留严格优于当前岗位的候选。
     *
     * @param candidates 候选列表
     * @param baseline   当前岗位 baseline，null表示不做基线过滤
     * @param context    排序上下文
     * @return 已按评分排序且通过基线过滤的候选列表
     */
    public List<OcRecommendCandidate> rank(List<OcRecommendCandidate> candidates,
                                           OcRecommendCandidate baseline,
                                           OcRankContext context) {
        List<OcRecommendCandidate> scored = candidates.stream()
                .map(candidate -> candidate.withScore(evaluate(candidate, context)))
                .toList();
        OcRecommendCandidate scoredBaseline = baseline == null
                ? null : baseline.withScore(evaluate(baseline, context));
        return scored.stream()
                .filter(candidate -> scoredBaseline == null
                        || sameOc(candidate, scoredBaseline)
                        || compareEconomy(candidate, scoredBaseline, context) < 0)
                .sorted(buildComparator(context))
                .toList();
    }

    /**
     * 构建完整排序比较器：评分降序、单位人天产出降序、期望人天升序、
     * 剩余空位数升序、ocId与slotId升序。
     *
     * @param context 排序上下文
     * @return 候选比较器
     */
    public Comparator<OcRecommendCandidate> buildComparator(OcRankContext context) {
        return Comparator
                .comparing((OcRecommendCandidate candidate) -> candidate.score().score(),
                        Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.score().unitMemberDayValue(),
                        Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.estimate().expectedMemberDays())
                .thenComparing(candidate -> emptySlots(context, candidate))
                .thenComparing(candidate -> candidate.oc().getId(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(candidate -> candidate.slot().getId(),
                        Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /**
     * 判断两个候选是否属于同一支OC。
     *
     * @param left  左候选
     * @param right 右候选
     * @return 同一OC返回true
     */
    private boolean sameOc(OcRecommendCandidate left, OcRecommendCandidate right) {
        return left.oc() != null && right.oc() != null
                && left.oc().getId() != null && left.oc().getId().equals(right.oc().getId());
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
        int byScore = right.score().score().compareTo(left.score().score());
        if (byScore != 0) {
            return byScore;
        }
        int byUnitValue = right.score().unitMemberDayValue().compareTo(left.score().unitMemberDayValue());
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
     * 排序上下文：一次推荐请求内共享的只读事实，避免为每个候选单独查库。
     *
     * @param emptySlotsByOc 每个OC的剩余空位数，作为第四决胜键与稀缺度的需求侧
     * @param userId         被推荐的成员ID
     * @param now            本次推荐的时间基准
     * @param slotIndex      按（OC名称, 等级, 岗位短编码）预分组的矩阵索引，随上下文只建一次
     * @author Bai
     * @version 1.9.4
     * @since 2026.10.23
     */
    public record OcRankContext(
            Map<Long, Integer> emptySlotsByOc,
            Long userId,
            LocalDateTime now,
            Map<String, List<TornFactionOcUserDO>> slotIndex) {

        /**
         * 归一化空值，避免排序时做空判断。
         */
        public OcRankContext {
            emptySlotsByOc = emptySlotsByOc == null ? Map.of() : Map.copyOf(emptySlotsByOc);
            slotIndex = slotIndex == null ? Map.of() : Map.copyOf(slotIndex);
        }

        /**
         * 由原始只读事实构造上下文并顺手建好岗位索引：一次推荐或一批分配只建这一份，
         * 之后每个候选的稀缺度查询都是O(1)。原始矩阵只用于建索引，不在上下文里另存一份。
         */
        public OcRankContext(List<TornFactionOcUserDO> factionOcData,
                             Map<Long, Integer> emptySlotsByOc,
                             Long userId,
                             LocalDateTime now) {
            this(emptySlotsByOc, userId, now, OcScarcityEstimator.indexBySlot(factionOcData));
        }

        /**
         * 复制上下文并替换被推荐成员，用于批量分配时逐人复用同一份只读事实与岗位索引。
         *
         * @param newUserId 新的成员ID
         * @return 替换成员后的上下文
         */
        public OcRankContext withUserId(Long newUserId) {
            return new OcRankContext(emptySlotsByOc, newUserId, now, slotIndex);
        }
    }
}
