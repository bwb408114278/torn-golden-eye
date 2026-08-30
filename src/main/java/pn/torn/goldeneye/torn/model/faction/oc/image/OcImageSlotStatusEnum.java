package pn.torn.goldeneye.torn.model.faction.oc.image;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * OC 表格图片槽位状态。
 * <p>
 * 由 {@link pn.torn.goldeneye.torn.service.faction.oc.image.OcImageStatusResolver}
 * 按“空转 > 缺少道具 > 准备完成 > 准备中”的优先级计算，一个已加入成员最多对应一个状态。
 *
 * @author Bai
 * @version 1.5.2
 * @since 2026.08.30
 */
@Getter
@RequiredArgsConstructor
public enum OcImageSlotStatusEnum {
    /**
     * 空槽：槽位没有用户，不展示状态 Emoji。
     */
    EMPTY("", "空槽"),
    /**
     * 空转：当前槽位成员准备进度为 0。
     */
    IDLE("💤", "空转"),
    /**
     * 缺少道具：本地道具快照明确为不可用。
     */
    MISSING_ITEM("⚠️", "缺少道具"),
    /**
     * 准备完成：当前槽位成员准备进度为 100。
     */
    READY("✅", "准备完成"),
    /**
     * 准备中：当前槽位成员准备进度在 0 到 100 之间。
     */
    PREPARING("⏳", "准备中"),
    /**
     * 未知：进度缺失或非法，不得猜测为其他状态。
     */
    UNKNOWN("", "未知");

    /**
     * 图片中使用的 Emoji；空槽和未知状态为空字符串。
     */
    private final String emoji;

    /**
     * 状态业务含义中文名。
     */
    private final String description;
}
