package pn.torn.goldeneye.torn.service.activity;

/**
 * V3 日快照完整性校验工具。
 * <p>
 * 只校验 Redis 压缩事实是否足以解释 observed 槽，不依赖 Spring、数据库或渲染逻辑。
 */
public final class ActivitySnapshotValidator {

    private static final int MAX_SLOTS = 96;

    private ActivitySnapshotValidator() {
    }

    /**
     * 校验用户 V3 日包的三个 Bitmap 是否完整。
     *
     * @param observed observed Bitmap
     * @param active 有效活跃 Bitmap
     * @param idle idle-only Bitmap
     * @return 三个 Bitmap 存在且每个 observed 槽可读取时返回 true
     */
    public static boolean isCompleteUserDay(byte[] observed, byte[] active, byte[] idle) {
        return isCompleteBitmapDay(observed, active, idle);
    }

    /**
     * 校验帮派 V3 日包的 observed Bitmap 与三组槽计数是否完整。
     *
     * @param observed observed Bitmap
     * @param activeCounts 有效活跃人数槽值
     * @param idleCounts idle-only 人数槽值
     * @param memberCounts 有效成员数槽值
     * @return 四个数组存在且每个 observed 槽可读取时返回 true
     */
    public static boolean isCompleteFactionDay(byte[] observed, byte[] activeCounts,
                                                byte[] idleCounts, byte[] memberCounts) {
        if (!hasBytes(observed) || !hasBytes(activeCounts)
                || !hasBytes(idleCounts) || !hasBytes(memberCounts)) {
            return false;
        }
        return observedSlotsAccessible(observed, activeCounts.length, idleCounts.length, memberCounts.length);
    }

    private static boolean isCompleteBitmapDay(byte[] observed, byte[] active, byte[] idle) {
        if (!hasBytes(observed) || !hasBytes(active) || !hasBytes(idle)) {
            return false;
        }
        return observedSlotsAccessible(observed, active.length, idle.length);
    }

    private static boolean observedSlotsAccessible(byte[] observed, int... companionLengths) {
        int observedSlots = Math.min(MAX_SLOTS, observed.length * Byte.SIZE);
        for (int slot = 0; slot < observedSlots; slot++) {
            if (!isBitSet(observed, slot)) {
                continue;
            }
            int byteIndex = slot / Byte.SIZE;
            for (int length : companionLengths) {
                if (byteIndex >= length) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isBitSet(byte[] bitmap, int slot) {
        int byteIndex = slot / Byte.SIZE;
        if (byteIndex >= bitmap.length) {
            return false;
        }
        int bitIndex = slot % Byte.SIZE;
        return (bitmap[byteIndex] & (1 << (Byte.SIZE - 1 - bitIndex))) != 0;
    }

    private static boolean hasBytes(byte[] value) {
        return value != null && value.length > 0;
    }
}
