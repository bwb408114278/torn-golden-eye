package pn.torn.goldeneye.torn.model.faction.crime.income;

/**
 * 单链事务处理结果类型。
 *
 * <p>由单链事务Worker返回给批量门面，用于区分成功、已结算、异常部分income、祖先缺失、
 * 等待后继节点与其他不再适用情况，真实失败则直接抛出异常，不在此枚举内。</p>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.08.03
 */
public enum SingleChainOutcomeEnum {
    /**
     * 整链明细与summary在本事务内成功生成并提交。
     */
    SUCCESS,
    /**
     * 叶子或整链已有完整income，无需重复生成。
     */
    ALREADY_CALCULATED,
    /**
     * 链内部分节点或成员income不完整（真子集、超集、重复或节点缺失），识别为异常，不新增任何收益。
     */
    ABNORMAL_PARTIAL_INCOME,
    /**
     * 链回溯发现祖先节点缺失、被逻辑删除、帮派不一致或存在环形引用，无法按完整链结算。
     */
    ABNORMAL_INCOMPLETE_CHAIN,
    /**
     * 成功的配置链父节点，真实后继尚未同步，需等待后续分页或刷新。
     */
    WAITING_PARENT,
    /**
     * 叶子已不再适用（数据被删除、状态变化、出现真实后继等），跳过但不视为失败。
     */
    NOT_CANDIDATE
}
