package pn.torn.goldeneye.torn.model.faction.crime.recommend;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * 岗位稀缺度分档枚举。
 *
 * <p>只用于对外解释「这个岗位有多难被替代」，<b>不参与排序</b>；排序键始终是「单位人天产出 × 开动紧迫度」。
 * 分档由替代概率直接映射，替代概率为null（该岗位在成功率矩阵里没有任何样本）时归入未知，
 * 按中性处理，既不奖励也不惩罚。</p>
 *
 * @author Bai
 * @version 1.9.4
 * @since 2026.10.23
 */
@Getter
@RequiredArgsConstructor
public enum OcRecommendOutcomeEnum {
    /**
     * 不可替代：除本人外没有其他达标成员，不填则整队直接归零
     */
    IRREPLACEABLE("不可替代"),
    /**
     * 稀缺：除本人外只有少数达标成员，补位速度慢
     */
    SCARCE("稀缺"),
    /**
     * 可替代：达标成员充足，不填通常可被迅速补上
     */
    SUBSTITUTABLE("可替代"),
    /**
     * 未知：岗位在成功率矩阵中无样本，或门槛刚调整，按中性处理
     */
    UNKNOWN("未知");

    /**
     * 中文展示标签
     */
    private final String label;

    /**
     * 由替代概率映射稀缺度分档。
     *
     * <p>口径：替代概率为null为未知；除本人外无人可填（概率不大于0）为不可替代；
     * 概率小于0.5为稀缺；其余为可替代。阈值只用于解释展示，排序不读取本方法。</p>
     *
     * @param substituteProbability 替代概率，null表示未知
     * @return 对应的稀缺度分档
     */
    public static OcRecommendOutcomeEnum fromSubstituteProbability(BigDecimal substituteProbability) {
        if (substituteProbability == null) {
            return UNKNOWN;
        }
        if (substituteProbability.compareTo(BigDecimal.ZERO) <= 0) {
            return IRREPLACEABLE;
        }
        if (substituteProbability.compareTo(new BigDecimal("0.5")) < 0) {
            return SCARCE;
        }
        return SUBSTITUTABLE;
    }
}
