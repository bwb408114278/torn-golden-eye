package pn.torn.goldeneye.torn.service.faction.oc.planning.evidence;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.model.faction.oc.OcPlanningRewardStatsDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcValueEvidence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * OC收益证据计算器。奖励物品价值拆分和收益口径统一集中在此，SQL、Renderer和各Service不得自行拆分。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@Component
public class OcRewardEvidenceCalculator {
    private static final String ITEMS_VALUE_SEPARATOR = "#";

    /**
     * 解析单条OC记录的完整收益口径：reward_money + Σ(split(reward_items_value, '#'))。
     *
     * <p>奖励物品值为空按0处理；格式非法、负数或奖励缺失的记录不作为奖励完整样本。</p>
     *
     * @param oc 历史OC记录
     * @return 完整收益；记录不满足奖励完整口径时返回空
     */
    public OptionalLong parseCompleteReward(TornFactionOcDO oc) {
        if (oc == null || oc.getRewardMoney() == null || oc.getRewardMoney() < 0) {
            return OptionalLong.empty();
        }
        long total = oc.getRewardMoney();
        String itemsValue = oc.getRewardItemsValue();
        if (itemsValue == null || itemsValue.isBlank()) {
            return OptionalLong.of(total);
        }
        for (String part : itemsValue.split(ITEMS_VALUE_SEPARATOR)) {
            String normalized = part.trim();
            if (normalized.isEmpty()) {
                return OptionalLong.empty();
            }
            long value;
            try {
                value = Long.parseLong(normalized);
            } catch (NumberFormatException exception) {
                return OptionalLong.empty();
            }
            if (value < 0) {
                return OptionalLong.empty();
            }
            total += value;
        }
        return OptionalLong.of(total);
    }

    /**
     * 将已完成状态的历史OC记录按OC规划键聚合为收益统计。
     *
     * @param completedOcs 已完成状态的历史OC记录
     * @return 按OC规划键索引的收益统计
     */
    public Map<String, OcPlanningRewardStatsDO> aggregate(List<TornFactionOcDO> completedOcs) {
        Map<String, Aggregator> aggregators = new HashMap<>();
        for (TornFactionOcDO oc : completedOcs) {
            String key = ocKey(oc);
            Aggregator aggregator = aggregators.computeIfAbsent(key,
                    ignored -> new Aggregator(oc.getRank(), oc.getName()));
            aggregator.attemptCount++;
            boolean successful = TornOcStatusEnum.SUCCESSFUL.getCode().equals(oc.getStatus());
            if (successful) {
                aggregator.successCount++;
                parseCompleteReward(oc).ifPresent(reward -> {
                    aggregator.rewardCompleteCount++;
                    aggregator.totalReward += reward;
                    aggregator.minReward = aggregator.minReward == null
                            ? reward : Math.min(aggregator.minReward, reward);
                });
            }
        }
        Map<String, OcPlanningRewardStatsDO> result = new HashMap<>();
        aggregators.forEach((key, aggregator) -> result.put(key, aggregator.build()));
        return result;
    }

    /**
     * 按业务冻结降级顺序构造价值证据。
     *
     * @param stats                 该OC的收益统计；无样本时为null
     * @param minSampleSize         业务最小有效样本数
     * @param incrementalMemberDays 增量剩余成员人天
     * @param expectedReleaseAt     预计完整释放时间
     * @return 价值证据
     */
    public OcValueEvidence buildEvidence(OcPlanningRewardStatsDO stats, Integer minSampleSize,
                                         int incrementalMemberDays,
                                         java.time.LocalDateTime expectedReleaseAt) {
        return buildEvidence(stats, minSampleSize, incrementalMemberDays, expectedReleaseAt,
                0, incrementalMemberDays, 1);
    }

    /**
     * 按业务冻结降级顺序构造价值证据，并显式携带第三层业务先验字段。
     *
     * @param stats                 该OC的收益统计；无样本时为null
     * @param minSampleSize         业务最小有效样本数
     * @param incrementalMemberDays 增量剩余成员人天
     * @param expectedReleaseAt     预计完整释放时间
     * @param highestRank           完整候选最高等级
     * @param totalRequiredMembers  完整候选总需人数
     * @param chainNodeCount        完整候选链节点数；普通OC为1
     * @return 价值证据
     */
    public OcValueEvidence buildEvidence(OcPlanningRewardStatsDO stats, Integer minSampleSize,
                                         int incrementalMemberDays,
                                         java.time.LocalDateTime expectedReleaseAt,
                                         int highestRank, int totalRequiredMembers,
                                         int chainNodeCount) {
        int requiredSamples = minSampleSize == null ? 0 : minSampleSize;
        if (stats != null && stats.rewardCompleteCount() >= Math.max(1, requiredSamples)
                && stats.observedRewardPerAttempt() != null) {
            return new OcValueEvidence(OcValueEvidence.Level.OBSERVED_REWARD,
                    stats.observedRewardPerAttempt(), incrementalMemberDays,
                    expectedReleaseAt, true, highestRank, totalRequiredMembers,
                    chainNodeCount);
        }
        if (stats != null && stats.rewardFloor() != null && stats.rewardFloor() > 0) {
            return new OcValueEvidence(OcValueEvidence.Level.REWARD_FLOOR,
                    BigDecimal.valueOf(stats.rewardFloor()), incrementalMemberDays,
                    expectedReleaseAt, true, highestRank, totalRequiredMembers,
                    chainNodeCount);
        }
        return new OcValueEvidence(OcValueEvidence.Level.PRIOR_ONLY, null,
                incrementalMemberDays, expectedReleaseAt, true,
                highestRank, totalRequiredMembers, chainNodeCount);
    }

    /**
     * 聚合完整链的价值证据：按最弱节点层级降级，链价值为节点价值合计。
     *
     * <p>任一节点金额证据不足时聚合链即金额证据不足，不得用于提高刷新建议；
     * 层级取最弱节点而非最强节点，避免强节点掩盖弱节点的降级事实。</p>
     *
     * @param nodeEvidences 链内全部节点（含根）的价值证据
     * @return 完整链聚合证据
     */
    public OcValueEvidence aggregateChainEvidence(List<OcValueEvidence> nodeEvidences) {
        if (nodeEvidences == null || nodeEvidences.isEmpty()) {
            return new OcValueEvidence(OcValueEvidence.Level.INSUFFICIENT, null,
                    0, null, false, 0, 0, 0);
        }
        OcValueEvidence.Level level = nodeEvidences.stream()
                .map(OcValueEvidence::level)
                .max(Enum::compareTo)
                .orElse(OcValueEvidence.Level.INSUFFICIENT);
        BigDecimal totalValue = null;
        boolean allValued = nodeEvidences.stream()
                .allMatch(evidence -> evidence.totalValue() != null);
        if (allValued) {
            totalValue = BigDecimal.ZERO;
            for (OcValueEvidence evidence : nodeEvidences) {
                totalValue = totalValue.add(evidence.totalValue());
            }
        }
        int memberDays = nodeEvidences.stream()
                .mapToInt(OcValueEvidence::incrementalMemberDays).sum();
        int highestRank = nodeEvidences.stream()
                .mapToInt(OcValueEvidence::highestRank).max().orElse(0);
        int totalMembers = nodeEvidences.stream()
                .mapToInt(OcValueEvidence::totalRequiredMembers).sum();
        int chainNodeCount = nodeEvidences.stream()
                .mapToInt(OcValueEvidence::chainNodeCount).sum();
        java.time.LocalDateTime earliestRelease = nodeEvidences.stream()
                .map(OcValueEvidence::expectedReleaseAt)
                .filter(java.util.Objects::nonNull)
                .max(java.time.LocalDateTime::compareTo).orElse(null);
        boolean usable = level != OcValueEvidence.Level.INSUFFICIENT;
        return new OcValueEvidence(level, totalValue, memberDays, earliestRelease, usable,
                highestRank, totalMembers, chainNodeCount);
    }

    /**
     * 计算当前快照下的增量剩余成员人天：剩余待加入岗位数的倒序加和。
     *
     * @param totalMembers OC完整岗位数
     * @param joinedCount  已加入成员数
     * @return 增量剩余成员人天
     */
    public int incrementalMemberDays(int totalMembers, int joinedCount) {
        int remaining = Math.max(0, totalMembers - joinedCount);
        return (remaining * (remaining + 1) / 2) + remaining * joinedCount;
    }

    private String ocKey(TornFactionOcDO oc) {
        return oc.getRank() + ":" + oc.getName();
    }

    /**
     * 单OC收益聚合中间状态。
     */
    private static final class Aggregator {
        private final int rank;
        private final String ocName;
        private int attemptCount;
        private int successCount;
        private int rewardCompleteCount;
        private long totalReward;
        private Long minReward;

        private Aggregator(int rank, String ocName) {
            this.rank = rank;
            this.ocName = ocName;
        }

        private OcPlanningRewardStatsDO build() {
            BigDecimal perAttempt = attemptCount == 0
                    ? null : BigDecimal.valueOf(totalReward)
                    .divide(BigDecimal.valueOf(attemptCount), 2, RoundingMode.DOWN);
            return new OcPlanningRewardStatsDO(rank, ocName, attemptCount, successCount,
                    rewardCompleteCount, totalReward, perAttempt, minReward);
        }
    }
}
