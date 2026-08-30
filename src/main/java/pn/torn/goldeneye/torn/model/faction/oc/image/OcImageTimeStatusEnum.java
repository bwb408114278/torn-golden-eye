package pn.torn.goldeneye.torn.model.faction.oc.image;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * OC 表格图片标题时间状态。
 * <p>
 * 由 {@link pn.torn.goldeneye.torn.service.faction.oc.image.OcImageTitleFormatter}
 * 根据 OC 状态、{@code readyTime} 和当前时间互斥计算；一张 OC 图片只出现一种时间文案。
 *
 * @author Bai
 * @version 1.5.2
 * @since 2026.08.30
 */
@Getter
@RequiredArgsConstructor
public enum OcImageTimeStatusEnum {
    /**
     * 没有时间文案。
     */
    NONE("无时间文案"),
    /**
     * 已停转：Recruiting 且当前时间已晚于 readyTime。
     */
    STOPPED("已停转"),
    /**
     * 停转倒计时：Recruiting 且未超过 readyTime，剩余不超过 24 小时。
     */
    STOP_COUNTDOWN("停转倒计时"),
    /**
     * 空转：Recruiting 或 Planning 且剩余准备链时间超过 24 小时。
     */
    IDLE("还需空转"),
    /**
     * 预计执行：Planning 且剩余时间不超过 24 小时。
     */
    PLANNED("预计执行");

    /**
     * 状态业务含义中文名。
     */
    private final String description;
}
