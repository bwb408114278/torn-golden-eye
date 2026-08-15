package pn.torn.goldeneye.torn.model.faction.crime.planning;

import lombok.Getter;

/**
 * OC时间线规划的稳定匿名原因码。只描述匿名阻断或状态原因，不暴露成员、岗位和排程。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@Getter
public enum OcPlanReasonCodeEnum {
    /**
     * 存在期限前无合格成员可以承担的义务。
     */
    NO_QUALIFIED_MEMBER_BEFORE_DEADLINE("存在期限前无合格成员承担的义务"),
    /**
     * 无法形成或替换下一完整完成—释放流动性锚点。
     */
    NO_REPLACEMENT_LIQUIDITY_ANCHOR("无法证明连续的完成—释放流动性锚点"),
    /**
     * 链节点无法按实例与配置唯一映射。
     */
    CHAIN_MAPPING_AMBIGUOUS("高阶链实例与配置无法唯一映射"),
    /**
     * 随机结果已变化，旧建议立即失效。
     */
    RANDOM_OUTCOME_CHANGED("随机结果已变化，需立即重新评估"),
    /**
     * 业务边界已到达，必须立即重新评估。
     */
    REPLAN_REQUIRED_NOW("已到达重新评估边界，需立即重新运行指令"),
    /**
     * 已启动链后继无法履约，已阻断全部新增刷新。
     */
    COMMITTED_CHAIN_BLOCKED("已启动链后继无法履约，已阻断全部新增刷新"),
    /**
     * 已有人OC缺少可证明的阶段时间，成员释放无法证明。
     */
    UNPROVABLE_OCCUPATION_PRESENT("存在无法证明释放时间的成员占用"),
    /**
     * 建议仅为当前预算内已证明安全下界。
     */
    SAFE_LOWER_BOUND_ONLY("建议次数为当前预算内已证明安全下界"),
    /**
     * 经济证据不足，不能据此提高刷新或停转建议。
     */
    ECONOMIC_EVIDENCE_INSUFFICIENT("收益证据不足，未据此提高刷新或停转建议");

    /**
     * 获取原因码对应的匿名中文说明。
     */
    private final String description;

    OcPlanReasonCodeEnum(String description) {
        this.description = description;
    }

}
