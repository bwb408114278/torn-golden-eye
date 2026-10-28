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

    private OcRecommendCandidate withDisplayRating(OcRecommendCandidate candidate, int anchor) {
        int group = groupOf(candidate, anchor);
        int rating = OcRecommendRating.ratingOf(group, candidate.oc().getRank(), isCore(candidate),
                candidate.score().score());
        return candidate.withScore(candidate.score().withDisplayRating(rating));
    }

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
     */
    private Comparator<OcRecommendCandidate> buildEconomicComparator(OcRankContext context, int anchor) {
        return buildSharedPrefix(context, anchor)
                .thenComparing(this::compareSameOcMarginal);
    }

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
     */
    private int compareSameOcMarginal(OcRecommendCandidate left, OcRecommendCandidate right) {
        if (!sameOcType(left, right)) {
            return 0;
        }
        return Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder())
                .compare(left.marginalValue(), right.marginalValue());
    }

    private boolean isCore(OcRecommendCandidate candidate) {
        return candidate.requirement().getPassRate() != null
                && candidate.requirement().getPassRate() >= CORE_THRESHOLD;
    }

    private boolean sameOcType(OcRecommendCandidate left, OcRecommendCandidate right) {
        return Objects.equals(left.oc().getName(), right.oc().getName())
                && Objects.equals(left.oc().getRank(), right.oc().getRank());
    }

    private boolean sameOc(OcRecommendCandidate left, OcRecommendCandidate right) {
        return left.oc() != null && right.oc() != null
                && left.oc().getId() != null && left.oc().getId().equals(right.oc().getId());
    }

    private int emptySlots(OcRankContext context, OcRecommendCandidate candidate) {
        if (context.emptySlotsByOc() == null || candidate.oc() == null) {
            return 0;
        }
        return context.emptySlotsByOc().getOrDefault(candidate.oc().getId(), 0);
    }

    /**
     * 排序上下文：一次推荐请求内共享的只读事实，避免为每个候选单独查库。
     */
    public record OcRankContext(
            Map<Long, Integer> emptySlotsByOc,
            Long userId,
            LocalDateTime now,
            Map<String, List<TornFactionOcUserDO>> slotIndex) {

        public OcRankContext {
            emptySlotsByOc = emptySlotsByOc == null ? Map.of() : Map.copyOf(emptySlotsByOc);
            slotIndex = slotIndex == null ? Map.of() : Map.copyOf(slotIndex);
        }

        public OcRankContext(List<TornFactionOcUserDO> factionOcData,
                             Map<Long, Integer> emptySlotsByOc,
                             Long userId,
                             LocalDateTime now) {
            this(emptySlotsByOc, userId, now, OcScarcityEstimator.indexBySlot(factionOcData));
        }

        public OcRankContext withUserId(Long newUserId) {
            return new OcRankContext(emptySlotsByOc, newUserId, now, slotIndex);
        }
    }
}
