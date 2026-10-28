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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 推荐评分与排序器。
 *
 * <p>先用核心岗非负ΔV确定最高胜任等级，再按主推荐、负边际后置、高等级后置分组。
 * 组内依次按等级、核心/大众、R6单位人天产出×紧迫度排序；只有同一OC的R6并列时才用ΔV选坑。
 * 无图或奖励不全的候选边际为null，不参与锚点计算，但仍保留并按R6推荐。</p>
 *
 * @author Bai
 * @version 1.9.5
 * @since 2026.10.23
 */
@Component
@RequiredArgsConstructor
public class OcRecommendRanker {
    private static final int SCORE_SCALE = 2;
    private static final int CORE_THRESHOLD = 65;
    private static final int MAIN_GROUP = 0;
    private static final int NEGATIVE_GROUP = 1;
    private static final int ABOVE_ANCHOR_GROUP = 2;

    private final OcScarcityEstimator scarcityEstimator;

    /**
     * 计算单个候选的R6推荐评分。
     *
     * @param candidate 候选岗位
     * @param context   排序上下文
     * @return R6推荐评分快照
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
     * 计算评分、最高胜任等级和展示分，并按同一比较器完成基线过滤与排序。
     *
     * @param candidates 候选岗位列表
     * @param baseline   成员当前岗位；未加入OC时为null
     * @param context    排序上下文
     * @return 完成基线过滤并按完整业务键排序的候选列表
     */
    public List<OcRecommendCandidate> rank(List<OcRecommendCandidate> candidates,
                                           OcRecommendCandidate baseline,
                                           OcRankContext context) {
        List<OcRecommendCandidate> evaluated = candidates.stream()
                .map(candidate -> candidate.withScore(evaluate(candidate, context)))
                .toList();
        OcRecommendCandidate evaluatedBaseline = baseline == null
                ? null : baseline.withScore(evaluate(baseline, context));
        int anchor = resolveAnchor(evaluated, evaluatedBaseline);

        List<OcRecommendCandidate> scored = evaluated.stream()
                .map(candidate -> withDisplayRating(candidate, anchor))
                .toList();
        OcRecommendCandidate scoredBaseline = evaluatedBaseline == null
                ? null : withDisplayRating(evaluatedBaseline, anchor);
        Comparator<OcRecommendCandidate> comparator = buildComparator(context, anchor);
        Comparator<OcRecommendCandidate> economicComparator = buildEconomicComparator(context, anchor);
        return scored.stream()
                .filter(candidate -> scoredBaseline == null
                        || sameOc(candidate, scoredBaseline)
                        || economicComparator.compare(candidate, scoredBaseline) < 0)
                .sorted(comparator)
                .toList();
    }

    /**
     * 最高核心胜任等级；没有核心胜任时退到最高非负岗位等级。
     *
     * @param candidates 候选岗位列表
     * @param baseline   成员当前岗位；未加入OC时为null
     * @return 最高胜任等级；没有可用DAG边际时返回Integer.MAX_VALUE
     */
    private int resolveAnchor(List<OcRecommendCandidate> candidates, OcRecommendCandidate baseline) {
        List<OcRecommendCandidate> all = new ArrayList<>(candidates);
        if (baseline != null) {
            all.add(baseline);
        }
        Integer coreAnchor = all.stream()
                .filter(candidate -> candidate.marginalValue() != null
                        && candidate.marginalValue().signum() >= 0 && isCore(candidate))
                .map(candidate -> candidate.oc().getRank())
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(null);
        if (coreAnchor != null) {
            return coreAnchor;
        }
        Integer positiveAnchor = all.stream()
                .filter(candidate -> candidate.marginalValue() != null && candidate.marginalValue().signum() >= 0)
                .map(candidate -> candidate.oc().getRank())
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(null);
        if (positiveAnchor != null) {
            return positiveAnchor;
        }
        return all.stream()
                .filter(candidate -> candidate.marginalValue() != null)
                .map(candidate -> candidate.oc().getRank())
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(Integer.MAX_VALUE);
    }

    /**
     * null边际表示回退，不等同于合法ΔV=0，也不限制锚点。
     *
     * @param candidate 候选岗位
     * @param anchor    最高胜任等级
     * @return 主推荐、负边际后置或高等级后置的分组编号
     */
    private int groupOf(OcRecommendCandidate candidate, int anchor) {
        if (candidate.marginalValue() == null) {
            return MAIN_GROUP;
        }
        if (candidate.oc().getRank() > anchor) {
            return ABOVE_ANCHOR_GROUP;
        }
        return candidate.marginalValue().signum() >= 0 ? MAIN_GROUP : NEGATIVE_GROUP;
    }

    /**
     * 按候选所在分组、等级、岗位类型和R6评分生成展示分。
     *
     * @param candidate 已计算R6评分的候选
     * @param anchor    最高胜任等级
     * @return 写入展示分的候选副本
     */
    private OcRecommendCandidate withDisplayRating(OcRecommendCandidate candidate, int anchor) {
        int group = groupOf(candidate, anchor);
        int rating = OcRecommendRating.ratingOf(group, candidate.oc().getRank(), isCore(candidate),
                candidate.score().score());
        return candidate.withScore(candidate.score().withDisplayRating(rating));
    }

    /**
     * 构建包含确定性决胜键的完整排序比较器。
     *
     * @param context 排序上下文
     * @param anchor  最高胜任等级
     * @return 完整排序比较器
     */
    private Comparator<OcRecommendCandidate> buildComparator(OcRankContext context, int anchor) {
        return buildSharedPrefix(context, anchor)
                .thenComparing(candidate -> candidate.oc().getId(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(this::compareSameOcMarginal)
                .thenComparing(candidate -> candidate.slot().getId(),
                        Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /**
     * baseline与最终排序共用的业务键，不含会诱发反复换岗的ID决胜。
     *
     * @param context 排序上下文
     * @param anchor  最高胜任等级
     * @return 用于当前岗位基线比较的业务比较器
     */
    private Comparator<OcRecommendCandidate> buildEconomicComparator(OcRankContext context, int anchor) {
        return buildSharedPrefix(context, anchor)
                .thenComparing(this::compareSameOcMarginal);
    }

    /**
     * 构建分组、等级、岗位类型与R6经济键组成的共用比较前缀。
     *
     * @param context 排序上下文
     * @param anchor  最高胜任等级
     * @return 完整排序与基线比较共用的比较器前缀
     */
    private Comparator<OcRecommendCandidate> buildSharedPrefix(OcRankContext context, int anchor) {
        return Comparator
                .comparingInt((OcRecommendCandidate candidate) -> groupOf(candidate, anchor))
                .thenComparing(candidate -> candidate.oc().getRank(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(this::isCore, Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.score().score(), Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.score().unitMemberDayValue(), Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.estimate().expectedMemberDays())
                .thenComparing(candidate -> emptySlots(context, candidate));
    }

    /**
     * ΔV只在同一OC名称与等级内选坑，绝不直接跨OC比较金额。
     *
     * @param left  左侧候选
     * @param right 右侧候选
     * @return 同一OC内按ΔV降序的比较结果；不同OC返回0
     */
    private int compareSameOcMarginal(OcRecommendCandidate left, OcRecommendCandidate right) {
        if (!sameOcType(left, right)) {
            return 0;
        }
        return Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder())
                .compare(left.marginalValue(), right.marginalValue());
    }

    /**
     * 按岗位门槛判断候选是否为核心岗。
     *
     * @param candidate 候选岗位
     * @return 门槛不低于65时返回true
     */
    private boolean isCore(OcRecommendCandidate candidate) {
        return candidate.requirement().getPassRate() != null
                && candidate.requirement().getPassRate() >= CORE_THRESHOLD;
    }

    /**
     * 判断两个候选是否属于相同名称和等级的OC。
     *
     * @param left  左侧候选
     * @param right 右侧候选
     * @return OC名称与等级均相同时返回true
     */
    private boolean sameOcType(OcRecommendCandidate left, OcRecommendCandidate right) {
        return Objects.equals(left.oc().getName(), right.oc().getName())
                && Objects.equals(left.oc().getRank(), right.oc().getRank());
    }

    /**
     * 判断两个候选是否属于同一支OC队伍。
     *
     * @param left  左侧候选
     * @param right 右侧候选
     * @return 两边OC ID均非空且相同时返回true
     */
    private boolean sameOc(OcRecommendCandidate left, OcRecommendCandidate right) {
        return left.oc() != null && right.oc() != null
                && left.oc().getId() != null && left.oc().getId().equals(right.oc().getId());
    }

    /**
     * 查询候选队伍的剩余空位数。
     *
     * @param context   排序上下文
     * @param candidate 候选岗位
     * @return 剩余空位数；上下文或OC缺失时返回0
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
     * @param emptySlotsByOc OC ID到剩余空位数的映射
     * @param userId         当前成员ID
     * @param now            本次排序使用的当前时间
     * @param slotIndex      归一化岗位编码到全帮派成员能力记录的索引
     */
    public record OcRankContext(
            Map<Long, Integer> emptySlotsByOc,
            Long userId,
            LocalDateTime now,
            Map<String, List<TornFactionOcUserDO>> slotIndex) {

        /**
         * 构造排序上下文并复制集合，防止调用方后续修改共享事实。
         *
         * @param emptySlotsByOc OC ID到剩余空位数的映射
         * @param userId         当前成员ID
         * @param now            本次排序使用的当前时间
         * @param slotIndex      归一化岗位编码到全帮派成员能力记录的索引
         */
        public OcRankContext {
            emptySlotsByOc = emptySlotsByOc == null ? Map.of() : Map.copyOf(emptySlotsByOc);
            slotIndex = slotIndex == null ? Map.of() : Map.copyOf(slotIndex);
        }

        /**
         * 根据全帮派能力记录构造排序上下文。
         *
         * @param factionOcData  全帮派成员能力记录
         * @param emptySlotsByOc OC ID到剩余空位数的映射
         * @param userId         当前成员ID
         * @param now            本次排序使用的当前时间
         */
        public OcRankContext(List<TornFactionOcUserDO> factionOcData,
                             Map<Long, Integer> emptySlotsByOc,
                             Long userId,
                             LocalDateTime now) {
            this(emptySlotsByOc, userId, now, OcScarcityEstimator.indexBySlot(factionOcData));
        }

        /**
         * 复制排序上下文并替换当前成员，供批量分配逐人使用。
         *
         * @param newUserId 新的当前成员ID
         * @return 替换成员后的排序上下文
         */
        public OcRankContext withUserId(Long newUserId) {
            return new OcRankContext(emptySlotsByOc, newUserId, now, slotIndex);
        }
    }
}
