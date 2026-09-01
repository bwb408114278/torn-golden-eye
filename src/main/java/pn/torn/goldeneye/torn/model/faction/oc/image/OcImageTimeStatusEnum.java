package pn.torn.goldeneye.torn.model.faction.oc.image;

/**
 * OC图片标题时间状态。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
public enum OcImageTimeStatusEnum {
    /**
     * 没有可展示的时间状态。
     */
    NONE,
    /**
     * 招募中的停转倒计时。
     */
    STOP_COUNTDOWN,
    /**
     * 招募已超过停转时间。
     */
    STOPPED,
    /**
     * 准备链仍需空转。
     */
    IDLE,
    /**
     * 预计开始执行。
     */
    PLANNED
}
