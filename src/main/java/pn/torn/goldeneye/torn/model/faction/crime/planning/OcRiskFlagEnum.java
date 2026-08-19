package pn.torn.goldeneye.torn.model.faction.crime.planning;

import lombok.Getter;

/**
 * OC时间线业务风险标记。多个风险可并存，与配置、证明状态分别输出。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@Getter
public enum OcRiskFlagEnum {
    /**
     * 本次规划窗口内存在确定性或经完整检查证明的全帮卡死风险。
     */
    DEADLOCK_RISK("当前存在全帮卡死或被迫拆队风险"),
    /**
     * 已投入义务或已启动链后继存在无法履约的风险。
     */
    HARD_OBLIGATION_AT_RISK("已投入义务或已启动链义务存在无法履约风险"),
    /**
     * 计划内无人OC存在过期压力。
     */
    EMPTY_OC_EXPIRY_PRESSURE("计划内无人OC存在过期压力"),
    /**
     * 时间线中存在（含已发生）可恢复停转。
     */
    RECOVERABLE_PAUSE_PRESENT("存在可恢复停转"),
    /**
     * 价值证据不足，不能据此提高刷新或停转建议。
     */
    ECONOMIC_EVIDENCE_INSUFFICIENT("收益证据不足，未据此提高刷新或停转建议");

    /**
     * 获取风险标记对应的匿名中文说明。
     */
    private final String description;

    OcRiskFlagEnum(String description) {
        this.description = description;
    }

}
