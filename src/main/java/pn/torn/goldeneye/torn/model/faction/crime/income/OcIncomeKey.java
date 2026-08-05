package pn.torn.goldeneye.torn.model.faction.crime.income;

/**
 * OC收益完整性业务键，以(OC ID, 用户ID, 岗位)唯一定位一条预期或实际收益记录。
 *
 * <p>用于income完整性审计：对每个链节点，根据有效完成岗位计算预期业务键集合，
 * 与活动income中的实际业务键精确比较，识别待计算、已结算与异常部分income。</p>
 *
 * @param ocId     OC节点ID
 * @param userId   参与用户ID
 * @param position 岗位
 * @author Bai
 * @version 1.2.12
 * @since 2026.08.04
 */
public record OcIncomeKey(
        long ocId,
        long userId,
        String position) {
}
