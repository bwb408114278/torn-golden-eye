package pn.torn.goldeneye.torn.service.faction.oc.recommend.rank;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 岗位替代概率估算器。
 *
 * <p>替代概率 = 除本人之外还能达标的成员数 / 该OC当前剩余空位数，每次推荐实时计算，
 * 不落库、不做平滑、不缓存成成员能力画像：能力会涨、门槛会调，任何持久化的画像第二天就过期。
 * 该(OC名称, 等级, 岗位)在成功率矩阵里一条记录都没有时返回null，由调用方按中性处理，
 * 既不当成0（会被误读成无人可替代）也不套用其它岗位的比例。</p>
 *
 * <p>一次推荐会评估几十个候选，而能力矩阵有数千行，因此支持先按岗位建索引再逐个候选查询，
 * 避免每个候选都全量扫描矩阵。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.23
 */
@Component
public class OcScarcityEstimator {
    /**
     * 替代概率的小数位数。
     */
    private static final int PROBABILITY_SCALE = 4;
    /**
     * 索引键分隔符。
     */
    private static final String KEY_SEPARATOR = "|";

    /**
     * 估算指定岗位的替代概率。
     *
     * @param requirement 岗位配置（已含帮派覆盖的成功率要求），可为空
     * @param matrix      帮派成功率矩阵（含全局默认行），可为空
     * @param userId      被推荐的成员ID，可为空
     * @param demand      该OC当前剩余空位数（含本候选），小于1时按1处理
     * @return 替代概率与可填人数；矩阵无该岗位样本时返回null
     */
    public OcSubstituteResult estimate(TornSettingOcSlotDO requirement, List<TornFactionOcUserDO> matrix,
                                       Long userId, int demand) {
        return estimateFromIndex(requirement, indexBySlot(matrix), userId, demand);
    }

    /**
     * 按（OC名称, 等级, 岗位短编码）把能力矩阵建成索引，供批量评估复用。
     *
     * @param matrix 帮派成功率矩阵，可为空
     * @return 岗位键到该岗位全部能力行的索引
     */
    public Map<String, List<TornFactionOcUserDO>> indexBySlot(List<TornFactionOcUserDO> matrix) {
        if (matrix == null || matrix.isEmpty()) {
            return Map.of();
        }
        return matrix.stream().collect(Collectors.groupingBy(this::slotKey));
    }

    /**
     * 用已建好的索引估算指定岗位的替代概率。
     *
     * @param requirement 岗位配置，可为空
     * @param slotIndex   岗位键索引，可为空
     * @param userId      被推荐的成员ID，可为空
     * @param demand      该OC当前剩余空位数（含本候选），小于1时按1处理
     * @return 替代概率与可填人数；矩阵无该岗位样本时返回null
     */
    public OcSubstituteResult estimateFromIndex(TornSettingOcSlotDO requirement,
                                                Map<String, List<TornFactionOcUserDO>> slotIndex,
                                                Long userId, int demand) {
        if (requirement == null || slotIndex == null || userId == null) {
            return null;
        }
        List<TornFactionOcUserDO> matched = slotIndex.get(slotKey(requirement));
        if (matched == null || matched.isEmpty()) {
            return null;
        }

        int requiredPassRate = requirement.getPassRate() == null ? 0 : requirement.getPassRate();
        Set<Long> eligibleUserIds = matched.stream()
                .filter(row -> row.getPassRate() != null && row.getPassRate() >= requiredPassRate)
                .map(TornFactionOcUserDO::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        int substituteCount = Math.max(0, eligibleUserIds.size() - (eligibleUserIds.contains(userId) ? 1 : 0));
        int slots = Math.max(1, demand);
        BigDecimal probability = BigDecimal.valueOf(substituteCount)
                .divide(BigDecimal.valueOf(slots), PROBABILITY_SCALE, RoundingMode.HALF_UP)
                .min(BigDecimal.ONE);
        return new OcSubstituteResult(probability, substituteCount);
    }

    /**
     * 构造岗位配置的索引键。
     */
    private String slotKey(TornSettingOcSlotDO requirement) {
        return requirement.getOcName() + KEY_SEPARATOR + requirement.getRank()
                + KEY_SEPARATOR + requirement.getSlotShortCode();
    }

    /**
     * 构造能力行所属岗位的索引键。
     */
    private String slotKey(TornFactionOcUserDO row) {
        return row.getOcName() + KEY_SEPARATOR + row.getRank() + KEY_SEPARATOR + row.getPosition();
    }

    /**
     * 岗位替代性估算结果。
     *
     * @param probability     替代概率，取值0到1
     * @param substituteCount 除本人之外可填该岗位的人数
     * @author Bai
     * @version 1.9.3
     * @since 2026.10.23
     */
    public record OcSubstituteResult(
            BigDecimal probability,
            Integer substituteCount) {
    }
}
