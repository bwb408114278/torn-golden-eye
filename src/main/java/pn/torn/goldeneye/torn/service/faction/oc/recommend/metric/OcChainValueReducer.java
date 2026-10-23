package pn.torn.goldeneye.torn.service.faction.oc.recommend.metric;

import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldEstimate;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldSourceEnum;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * 链式OC的价值归约器。
 *
 * <p>归约规则只有两条，集中在这里，调用方不得重复实现：
 * 一是奖励只取叶子（顺序号最大的那一步），前置步骤的奖励一律不计；
 * 二是人天累加全部步骤，因为前置步骤虽然不付钱，却真实消耗帮派人力。
 * 「实测链」与「配置链」的差异只体现在传入的取值函数上，归约算法本身共用。</p>
 *
 * @param <T> 链步骤的输入类型
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.23
 */
public final class OcChainValueReducer<T> {

    /**
     * 归约一条链的各步骤估算值。
     *
     * @param steps     链步骤列表，不能为空，顺序可以无序（以stepIndex最大者为叶子）
     * @param valueOf   把单个步骤折算成估算值
     * @param stepIndex 取出步骤顺序号
     * @return 整条链的估算值：奖励取叶子，人天累加全部步骤
     * @throws IllegalArgumentException 步骤列表为空时抛出
     */
    public OcYieldEstimate reduce(List<T> steps, Function<T, OcYieldEstimate> valueOf, ToIntFunction<T> stepIndex) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("链步骤不能为空");
        }

        List<OcYieldEstimate> values = new ArrayList<>(steps.size());
        BigDecimal memberDays = BigDecimal.ZERO;
        boolean allMeasured = true;
        int leafPosition = 0;
        int leafIndex = Integer.MIN_VALUE;
        for (int position = 0; position < steps.size(); position++) {
            T step = steps.get(position);
            OcYieldEstimate estimate = valueOf.apply(step);
            values.add(estimate);
            memberDays = memberDays.add(estimate.expectedMemberDays());
            if (estimate.source() != OcYieldSourceEnum.MEASURED) {
                allMeasured = false;
            }
            int index = stepIndex.applyAsInt(step);
            if (index > leafIndex) {
                leafIndex = index;
                leafPosition = position;
            }
        }

        OcYieldSourceEnum source = resolveSource(allMeasured, values);
        return new OcYieldEstimate(values.get(leafPosition).expectedNetReward(), memberDays, source);
    }

    /**
     * 判定整条链的估算来源：全部步骤都有实测样本才算实测；
     * 多步链只要有一处是映射即为链映射；单步链沿用该步自身来源。
     */
    private OcYieldSourceEnum resolveSource(boolean allMeasured, List<OcYieldEstimate> values) {
        if (allMeasured) {
            return OcYieldSourceEnum.MEASURED;
        }
        return values.size() > 1 ? OcYieldSourceEnum.CHAIN_MAPPED : values.getFirst().source();
    }
}
