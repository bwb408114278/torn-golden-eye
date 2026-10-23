package pn.torn.goldeneye.torn.service.faction.oc.recommend.metric;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.model.faction.oc.OcIncomeYieldStatsDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldEstimate;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldSourceEnum;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OC产出估算注册表。
 *
 * <p>按来源选择实现：该(OC名称, 等级, 帮派)在收益记录中的成功样本数达到阈值就走实测，
 * 否则走等级映射（链式OC为链映射）。判定只看样本量，不看是否在大锅饭名单内，
 * 因此名单外的新OC也能立即参与排序。以后新增估算来源只需增加实现类，调用方不变。</p>
 *
 * <p>批量估算时按帮派一次性加载实测统计，避免为每个候选单独查库（批量分配场景下用户数乘以候选数
 * 会把单次查询放大成灾难）。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.23
 */
@Component
@RequiredArgsConstructor
public class OcYieldEstimatorRegistry {
    private final List<OcYieldEstimator> estimators;
    private final MeasuredOcYieldEstimator measuredEstimator;
    private final MappedOcYieldEstimator mappedEstimator;

    /**
     * 估算单个OC的期望产出。
     *
     * @param oc           OC数据
     * @param snapshotTime 本次推荐的时间基准
     * @return 期望产出估算值
     */
    public OcYieldEstimate estimate(TornFactionOcDO oc, LocalDateTime snapshotTime) {
        OcIncomeYieldStatsDO stats = measuredEstimator.findStats(
                oc.getFactionId(), oc.getName(), oc.getRank());
        return dispatch(oc, stats, snapshotTime);
    }

    /**
     * 批量估算多个OC的期望产出，按帮派一次性加载实测统计后逐个估算。
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

        Map<String, OcIncomeYieldStatsDO> statsMap = loadStats(ocList);
        Map<Long, OcYieldEstimate> estimates = HashMap.newHashMap(ocList.size());
        for (TornFactionOcDO oc : ocList) {
            OcIncomeYieldStatsDO stats = statsMap.get(statsKey(oc.getFactionId(), oc.getName(), oc.getRank()));
            estimates.put(oc.getId(), dispatch(oc, stats, snapshotTime));
        }
        return estimates;
    }

    /**
     * 按实测样本量解析估算来源。
     *
     * @param oc    OC数据
     * @param stats 该OC的实测统计，可为空
     * @return 估算来源
     */
    public OcYieldSourceEnum resolveSource(TornFactionOcDO oc, OcIncomeYieldStatsDO stats) {
        if (measuredEstimator.hasEnoughSamples(stats)) {
            return OcYieldSourceEnum.MEASURED;
        }
        return mappedEstimator.isChainedOc(oc) ? OcYieldSourceEnum.CHAIN_MAPPED : OcYieldSourceEnum.MAPPED;
    }

    /**
     * 按来源分派到对应实现。
     *
     * <p>实测来源直接使用已加载的统计，避免通用入口重复查库；其余来源走策略入口。</p>
     */
    private OcYieldEstimate dispatch(TornFactionOcDO oc, OcIncomeYieldStatsDO stats,
                                     LocalDateTime snapshotTime) {
        OcYieldSourceEnum source = resolveSource(oc, stats);
        if (source == OcYieldSourceEnum.MEASURED) {
            return applyChainComposition(measuredEstimator.estimate(oc, stats, snapshotTime), oc);
        }
        return findEstimator(source).estimate(oc, snapshotTime);
    }

    /**
     * 把单步实测估算折算到整链口径：叶子的期望净收益乘以前置步骤成功率，人天累加全部步骤。
     *
     * <p>实测聚合只能看到单个OC自己的收益与占用，不包含前置步骤的人力成本；
     * 不折算会把链式OC的单位人天产出高估，从而把多步链排到不该有的位置。</p>
     */
    private OcYieldEstimate applyChainComposition(OcYieldEstimate stepEstimate, TornFactionOcDO oc) {
        MappedOcYieldEstimator.OcChainSummary chain = mappedEstimator.summarizeChain(oc);
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
