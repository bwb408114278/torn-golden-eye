package pn.torn.goldeneye.torn.service.activity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V3 日快照完整性校验测试。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.08.28
 */
@DisplayName("活跃度 V3 日快照完整性校验测试")
class ActivitySnapshotValidatorTest {

    @Test
    @DisplayName("完整用户日包应通过校验")
    void completeUserDay_isAccepted() {
        assertTrue(ActivitySnapshotValidator.isCompleteUserDay(
                bitmapWithFirstSlot(), bitmapWithFirstSlot(), bitmapWithFirstSlot()));
    }

    @Test
    @DisplayName("完整帮派日包应通过校验")
    void completeFactionDay_isAccepted() {
        assertTrue(ActivitySnapshotValidator.isCompleteFactionDay(
                bitmapWithFirstSlot(), new byte[96], new byte[96], new byte[96]));
    }

    @Test
    @DisplayName("observed 槽超出 companion 数组时应拒绝")
    void observedSlotOutsideCompanion_isRejected() {
        byte[] observed = new byte[12];
        observed[1] = (byte) 0x80;

        assertFalse(ActivitySnapshotValidator.isCompleteUserDay(
                observed, new byte[1], new byte[1]));
        assertFalse(ActivitySnapshotValidator.isCompleteFactionDay(
                observed, new byte[1], new byte[1], new byte[1]));
    }

    @Test
    @DisplayName("缺失 companion 数组时应拒绝")
    void missingCompanion_isRejected() {
        assertFalse(ActivitySnapshotValidator.isCompleteUserDay(
                new byte[12], null, new byte[12]));
        assertFalse(ActivitySnapshotValidator.isCompleteFactionDay(
                new byte[12], new byte[96], null, new byte[96]));
    }

    private static byte[] bitmapWithFirstSlot() {
        byte[] bitmap = new byte[12];
        bitmap[0] = (byte) 0x80;
        return bitmap;
    }
}
