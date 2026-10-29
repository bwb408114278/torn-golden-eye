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
import java.util.*;

/**
 * 推荐评分与排序器。
 *
 * <p>先用核心岗非负ΔV确定最高胜任等级，再按主推荐、负边际后置、高等级后置分组。
 * 现有队伍不再按长期单位人天收益排序，而是先比较真实停转时间；同时间再比较等级、
 * 核心/大众、个人DAG边际和剩余空位。同一ocId内只按ΔV选坑，同名不同队伍不得混用。</p>
 *
 * @author Bai
 * @version 1.9.6
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
    private static final int CORE_TIME_TOLERANCE_HOURS = 8;
    private static final int NEW_TEAM_WAIT_MINUTES = 150;

    private final OcScarcityEstimator scarcityEstimator;

    /**
     * 计算单个候选的R6诊断评分；该评分不再决定现有队伍的跨OC顺序。
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
     * 计算最高胜任等级，按真实停转时间与DAG边际排序，并生成允许并列的展示分。
     *
     * @param candidates 候选岗位列表
     * @param baseline   成员当前岗位；未加入OC时为null
     * @param context    排序上下文
     * @return 完成基线过滤并按完整业务规则排序的候选列表
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

        List<OcRecommendCandidate> filtered = evaluated.stream()
                .filter(candidate -> evaluatedBaseline == null
                        || sameOc(candidate, evaluatedBaseline)
                        || compareBusinessPriority(candidate, evaluatedBaseline, context, anchor) < 0)
                .toList();
        List<OcRecommendCandidate> sorted = sortCandidates(filtered, context, anchor);
        return withDisplayRatings(sorted, context, anchor);
    }

    /**
     * 最高核心胜任层级；没有核心胜任时退到最高非负岗位层级。
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
                .map(this::effectiveTier)
                .max(Integer::compareTo)
                .orElse(null);
        if (coreAnchor != null) {
            return coreAnchor;
        }
        Integer positiveAnchor = all.stream()
                .filter(candidate -> candidate.marginalValue() != null && candidate.marginalValue().signum() >= 0)
                .map(this::effectiveTier)
                .max(Integer::compareTo)
                .orElse(null);
        if (positiveAnchor != null) {
            return positiveAnchor;
        }
        return all.stream()
                .filter(candidate -> candidate.marginalValue() != null)
                .map(this::effectiveTier)
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
        if (effectiveTier(candidate) > anchor) {
            return ABOVE_ANCHOR_GROUP;
        }
        return candidate.marginalValue().signum() >= 0 ? MAIN_GROUP : NEGATIVE_GROUP;
    }

    /**
     * 使用稳定选择排序执行成对业务规则，避免核心时间容差等条件规则破坏比较器传递性。
     *
     * @param candidates 待排序候选
     * @param context    排序上下文
     * @param anchor     最高胜任等级
     * @return 按业务优先级排序的新列表
     */
    private List<OcRecommendCandidate> sortCandidates(List<OcRecommendCandidate> candidates,
                                                      OcRankContext context, int anchor) {
        List<OcRecommendCandidate> remaining = new ArrayList<>(candidates);
        remaining.sort(this::compareStableIds);
        List<OcRecommendCandidate> result = new ArrayList<>(remaining.size());
        while (!remaining.isEmpty()) {
            int bestIndex = 0;
            for (int index = 1; index < remaining.size(); index++) {
                if (compareBusinessPriority(remaining.get(index), remaining.get(bestIndex), context, anchor) < 0) {
                    bestIndex = index;
                }
            }
            result.add(remaining.remove(bestIndex));
        }
        return result;
    }

    /**
     * 比较两个候选的业务优先级；返回值小于0表示左侧应排在前面。
     *
     * @param left    左侧候选
     * @param right   右侧候选
     * @param context 排序上下文
     * @param anchor  最高胜任等级
     * @return 业务优先级比较结果，0表示业务并列
     */
    private int compareBusinessPriority(OcRecommendCandidate left, OcRecommendCandidate right,
                                        OcRankContext context, int anchor) {
        int groupCompare = Integer.compare(groupOf(left, anchor), groupOf(right, anchor));
        if (groupCompare != 0) {
            return groupCompare;
        }
        if (sameOc(left, right)) {
            return compareMarginal(left, right);
        }

        int timeCompare = compareReadyTime(left, right, context);
        if (timeCompare != 0) {
            return timeCompare;
        }
        int tierCompare = Integer.compare(effectiveTier(right), effectiveTier(left));
        if (tierCompare != 0) {
            return tierCompare;
        }
        if (sameRankAndCoreType(left, right)) {
            int marginalCompare = compareMarginal(left, right);
            if (marginalCompare != 0) {
                return marginalCompare;
            }
        }
        return Integer.compare(emptySlots(context, left), emptySlots(context, right));
    }

    /**
     * 比较真实停转时间；同rank跨OC的核心岗对大众岗享有8小时时间容差。
     *
     * @param left    左侧候选
     * @param right   右侧候选
     * @param context 排序上下文
     * @return 较早者排在前面；已停转按实际停转先后比较
     */
    private int compareReadyTime(OcRecommendCandidate left, OcRecommendCandidate right, OcRankContext context) {
        boolean coreTolerance = Objects.equals(left.oc().getRank(), right.oc().getRank())
                && isCore(left) != isCore(right);
        LocalDateTime leftTime = readyTimeOf(left, context);
        LocalDateTime rightTime = readyTimeOf(right, context);
        if (coreTolerance) {
            leftTime = leftTime.minusHours(isCore(left) ? CORE_TIME_TOLERANCE_HOURS : 0);
            rightTime = rightTime.minusHours(isCore(right) ? CORE_TIME_TOLERANCE_HOURS : 0);
        }
        return leftTime.compareTo(rightTime);
    }

    /**
     * 获取候选用于排序的时间；新队位于明确等待2小时与3小时之间。
     *
     * @param candidate 候选岗位
     * @param context   排序上下文
     * @return 实际readyTime，或新队的2.5小时虚拟时间层
     */
    private LocalDateTime readyTimeOf(OcRecommendCandidate candidate, OcRankContext context) {
        LocalDateTime readyTime = candidate.oc().getReadyTime();
        return readyTime == null ? context.now().plusMinutes(NEW_TEAM_WAIT_MINUTES) : readyTime;
    }

    /**
     * 计算同时间使用的等级层级：核心岗相当于提高一级，7级核心与8级大众并列。
     *
     * @param candidate 候选岗位
     * @return 等级与岗位类型合成的层级
     */
    private int effectiveTier(OcRecommendCandidate candidate) {
        int rank = candidate.oc().getRank() == null ? 0 : candidate.oc().getRank();
        return rank + (isCore(candidate) ? 1 : 0);
    }

    /**
     * 判断两个候选是否属于同rank且同为核心或大众岗。
     *
     * @param left  左侧候选
     * @param right 右侧候选
     * @return 同rank且岗位类型一致时返回true
     */
    private boolean sameRankAndCoreType(OcRecommendCandidate left, OcRecommendCandidate right) {
        return Objects.equals(left.oc().getRank(), right.oc().getRank()) && isCore(left) == isCore(right);
    }

    /**
     * 按个人DAG边际降序比较；缺图或缺奖励的null排在真实边际之后。
     *
     * @param left  左侧候选
     * @param right 右侧候选
     * @return ΔV高者排前，双方相同或均为null时返回0
     */
    private int compareMarginal(OcRecommendCandidate left, OcRecommendCandidate right) {
        return Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder())
                .compare(left.marginalValue(), right.marginalValue());
    }

    /**
     * 为排序结果生成单调不减的展示分，业务并列候选使用相同分数。
     *
     * @param sorted  已排序候选
     * @param context 排序上下文
     * @param anchor  最高胜任等级
     * @return 写入展示分的候选列表
     */
    private List<OcRecommendCandidate> withDisplayRatings(List<OcRecommendCandidate> sorted,
                                                          OcRankContext context, int anchor) {
        List<OcRecommendCandidate> result = new ArrayList<>(sorted.size());
        OcRecommendCandidate previous = null;
        int priority = 0;
        for (OcRecommendCandidate candidate : sorted) {
            if (previous != null && compareBusinessPriority(previous, candidate, context, anchor) != 0) {
                priority++;
            }
            int rating = OcRecommendRating.ratingOfOrder(priority);
            result.add(candidate.withScore(candidate.score().withDisplayRating(rating)));
            previous = candidate;
        }
        return result;
    }

    /**
     * 比较确定性ID决胜键，不参与业务并列判断。
     *
     * @param left  左侧候选
     * @param right 右侧候选
     * @return ocId与slotId升序比较结果
     */
    private int compareStableIds(OcRecommendCandidate left, OcRecommendCandidate right) {
        int ocCompare = Comparator.nullsLast(Comparator.<Long>naturalOrder())
                .compare(left.oc().getId(), right.oc().getId());
        if (ocCompare != 0) {
            return ocCompare;
        }
        return Comparator.nullsLast(Comparator.<Long>naturalOrder())
                .compare(left.slot().getId(), right.slot().getId());
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

    }
}
