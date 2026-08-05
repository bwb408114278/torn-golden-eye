package pn.torn.goldeneye.torn.model.faction.crime.income;

/**
 * 链income完整性结论。
 *
 * <p>根据预期业务键集合与实际活动income业务键集合的精确比较得出，供批量门面预分类
 * 与单链事务Worker复用。</p>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.08.04
 */
public enum IncomeCompletenessEnum {
    /**
     * 实际业务键为空，链待计算。
     */
    PENDING,
    /**
     * 实际业务键与预期业务键完全一致，且整链所有节点完整，已结算。
     */
    ALREADY_CALCULATED,
    /**
     * 实际业务键是预期业务键的真子集、超集、存在重复或链节点缺失，异常部分income。
     */
    ABNORMAL_PARTIAL_INCOME
}
