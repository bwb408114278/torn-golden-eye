package pn.torn.goldeneye.torn.model.faction.oc.image;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * OC图片槽位状态及其唯一展示Emoji。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
@Getter
@RequiredArgsConstructor
public enum OcImageSlotStatusEnum {
    /**
     * 空槽，不展示Emoji。
     */
    EMPTY(""),
    /**
     * 成员进度为零，表示空转。
     */
    IDLE("💤"),
    /**
     * 明确需要且不可用的道具。
     */
    MISSING_ITEM("⚠️"),
    /**
     * 成员准备进度已达到100%。
     */
    READY("✅"),
    /**
     * 成员正在准备。
     */
    PREPARING("⏳"),
    /**
     * 快照进度缺失或非法，不展示Emoji。
     */
    UNKNOWN("");

    /**
     * 状态对应的原始Unicode Emoji。
     */
    private final String emoji;
}
