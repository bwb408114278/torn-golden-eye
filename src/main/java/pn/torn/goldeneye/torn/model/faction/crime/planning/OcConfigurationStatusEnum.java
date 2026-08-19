package pn.torn.goldeneye.torn.model.faction.crime.planning;

/**
 * OC规划配置状态。只描述配置维度，不与求解证明状态混用。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public enum OcConfigurationStatusEnum {
    /**
     * 配置完整有效，可以进入自动规划。
     */
    VALID,
    /**
     * 配置存在确定性错误，自动规划必须关闭，两个刷新池均为0。
     */
    INVALID,
    /**
     * 配置缺少必要数据尚未补全，自动规划暂不可用。
     */
    INCOMPLETE
}
