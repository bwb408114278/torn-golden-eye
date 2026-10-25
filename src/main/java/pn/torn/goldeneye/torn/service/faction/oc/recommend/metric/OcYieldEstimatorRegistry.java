package pn.torn.goldeneye.torn.service.faction.oc.recommend.metric;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.OcIncomeYieldStatsDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldEstimate;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldSourceEnum;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcRecommendTunings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OC产出估算注册表。
 *
 * <p>对外只提供一个批量入口：一次请求查一次收益统计、查一次岗位编制，在内存里完成两种来源的
 * 可信度加权，避免为每个候选单独查库（批量分配场景下用户数乘以候选数会把单次查询放大成灾难）。</p>
 *
 * <p>两种来源同一量纲，按样本量加权而不是二选一：实测值权重为「尝试次数÷(尝试次数+
 * {@link OcRecommendTunings#MEASURED_PRIOR_ATTEMPTS})」，不足部分由等级映射值补齐。
 * 2026.10.25之前是「成功3次以上走实测、否则整体退回映射」的硬门槛，那会造成两个问题：
 * 一是6次成功的样本被当成真值顶到池子最前面（标准误约±28%），二是恰好差一次样本的OC
 * 整段退回等级阶梯，而阶梯对个别OC的偏差可达±40%，比带噪声的实测更离谱。</p>
 *
 * <p>人天口径由{@link OcYieldContext#teamSize}统一解析：目录配置优先，缺失时用该队实际岗位数。</p>
 *
 * @author Bai
 * @version 1.9.4
 * @since 2026.10.23
 */
@Component
@RequiredArgsConstructor
public class OcYieldEstimatorRegistry {
    /**
     * 加权后期望净产出的小数位数。
     */
    private static final int REWARD_SCALE = 2;

    private final List<OcYieldEstimator> estimators;
    private final MeasuredOcYieldEstimator measuredEstimator;
    private final MappedOcYieldEstimator mappedEstimator;
    private final TornFactionOcSlotDAO ocSlotDao;

    /**
     * 批量估算多个OC的期望产出，按帮派一次性加载实测统计与岗位编制后逐个估算。
     *
     * @param ocList       OC列表
     * @param snapshotTime 本次推荐的时间基准
     * @return Key为OC ID，值为期望产出估算值；OC ID为空时不写入结果
     */
    public Map<Long, OcYieldEstimate> estimateAll(Collection<TornFactionOcDO> ocList,
                                                  LocalDateTime snapshotTime) {
        if (ocList == null || ocList.isEmpty()) {
            return Map.of();
        }

        OcYieldContext context = buildContext(ocList, snapshotTime);
        Map<String, OcIncomeYieldStatsDO> statsMap = loadStats(ocList);
        Map<Long, OcYieldEstimate> estimates = HashMap.newHashMap(ocList.size());
        for (TornFactionOcDO oc : ocList) {
            OcIncomeYieldStatsDO stats = statsMap.get(statsKey(oc.getFactionId(), oc.getName(), oc.getRank()));
            estimates.put(oc.getId(), dispatch(oc, stats, context));
        }
        return estimates;
    }

    /**
     * 构造本次批量估算的只读上下文：时间基准与每支队的实际岗位数（key为「OC名称+等级」）。
     *
     * <p>岗位数只查一次：同名OC可能存在多个实例，同一「名称+等级」取实例间最大值，
     * 而不是把多个实例的岗位数相加（相加会把编制放大到实例数倍）。</p>
     */
    private OcYieldContext buildContext(Collection<TornFactionOcDO> ocList, LocalDateTime snapshotTime) {
        List<Long> ocIds = ocList.stream()
                .map(TornFactionOcDO::getId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, Integer> slotCountByOc = new HashMap<>();
        for (TornFactionOcSlotDO slot : ocSlotDao.queryListByOcId(ocIds)) {
            slotCountByOc.merge(slot.getOcId(), 1, Integer::sum);
        }

        Map<String, Integer> teamSizeByOc = HashMap.newHashMap(ocList.size());
        for (TornFactionOcDO oc : ocList) {
            int slotCount = slotCountByOc.getOrDefault(oc.getId(), 0);
            if (slotCount > 0) {
                teamSizeByOc.merge(OcYieldContext.keyOf(oc.getName(), oc.getRank()), slotCount, Math::max);
            }
        }
        return new OcYieldContext(snapshotTime, teamSizeByOc);
    }

    /**
     * 分派单个OC：有历史实例时把实测值与映射值按可信度加权，否则整体走映射。
     */
    private OcYieldEstimate dispatch(TornFactionOcDO oc, OcIncomeYieldStatsDO stats,
                                     OcYieldContext context) {
        OcYieldEstimate prior = findEstimator(fallbackSource(oc)).estimate(oc, context);
        if (!measuredEstimator.hasHistory(stats)) {
            return prior;
        }
        OcYieldEstimate measured = applyChainComposition(
                measuredEstimator.estimate(oc, stats, context), oc, context);
        return blend(measured, prior, measuredEstimator.attemptsOf(stats));
    }

    /**
     * 把实测值与映射值按样本量加权：尝试次数越多越信任实测，不足部分由等级映射补齐。
     */
    private OcYieldEstimate blend(OcYieldEstimate measured, OcYieldEstimate prior, int attempts) {
        BigDecimal measuredWeight = BigDecimal.valueOf(attempts);
        BigDecimal priorWeight = BigDecimal.valueOf(OcRecommendTunings.MEASURED_PRIOR_ATTEMPTS);
        BigDecimal totalWeight = measuredWeight.add(priorWeight);
        BigDecimal reward = measured.expectedNetReward().multiply(measuredWeight)
                .add(prior.expectedNetReward().multiply(priorWeight))
                .divide(totalWeight, REWARD_SCALE, RoundingMode.HALF_UP);
        return new OcYieldEstimate(reward, measured.expectedMemberDays(), measured.source());
    }

    /**
     * 解析没有实测时的估算来源：链式OC走链映射，其余走等级映射。
     */
    private OcYieldSourceEnum fallbackSource(TornFactionOcDO oc) {
        return mappedEstimator.isChainedOc(oc) ? OcYieldSourceEnum.CHAIN_MAPPED : OcYieldSourceEnum.MAPPED;
    }

    /**
     * 把单步实测估算折算到整链口径：叶子的期望净收益乘以前置步骤成功率，人天累加全部步骤。
     *
     * <p>实测聚合只能看到单个OC自己的收益与占用，不包含前置步骤的人力成本；
     * 不折算会把链式OC的单位人天产出高估，从而把多步链排到不该有的位置。</p>
     */
    private OcYieldEstimate applyChainComposition(OcYieldEstimate stepEstimate, TornFactionOcDO oc,
                                                  OcYieldContext context) {
        MappedOcYieldEstimator.OcChainSummary chain = mappedEstimator.summarizeChain(oc, context);
        if (!chain.chained()) {
            return stepEstimate;
        }
        return new OcYieldEstimate(stepEstimate.expectedNetReward().multiply(chain.prefixSuccessRate()),
                chain.memberDays(), stepEstimate.source());
    }

    /**
     * 查找承接指定来源的估算实现。
     */
    private OcYieldEstimator findEstimator(OcYieldSourceEnum source) {
        return estimators.stream()
                .filter(estimator -> estimator.supports(source))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少OC产出估算实现: " + source));
    }

    /**
     * 按帮派批量加载实测产出统计，并展平为「帮派+名称+等级」索引。
     */
    private Map<String, OcIncomeYieldStatsDO> loadStats(Collection<TornFactionOcDO> ocList) {
        Set<Long> factionIds = ocList.stream()
                .map(TornFactionOcDO::getFactionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, OcIncomeYieldStatsDO> statsMap = new HashMap<>();
        for (Long factionId : factionIds) {
            List<OcIncomeYieldStatsDO> rows = new ArrayList<>(measuredEstimator.loadStats(factionId));
            for (OcIncomeYieldStatsDO row : rows) {
                statsMap.put(statsKey(row.getFactionId(), row.getOcName(), row.getRank()), row);
            }
        }
        return statsMap;
    }

    /**
     * 构造实测统计索引键。
     */
    private String statsKey(Long factionId, String ocName, Integer rank) {
        return factionId + "|" + ocName + "|" + rank;
    }
}
