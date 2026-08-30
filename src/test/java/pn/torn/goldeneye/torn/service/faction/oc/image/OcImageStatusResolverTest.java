package pn.torn.goldeneye.torn.service.faction.oc.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.oc.image.OcImageSlotStatusEnum;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OC 表格图片槽位状态解析测试。
 * <p>
 * 验证空槽、空转、准备中、准备完成、缺少道具、优先级和未知快照降级。
 *
 * @author Bai
 * @version 1.5.2
 * @since 2026.08.30
 */
@DisplayName("OC槽位状态解析测试")
class OcImageStatusResolverTest {

    private final OcImageStatusResolver resolver = new OcImageStatusResolver();

    @Test
    @DisplayName("空槽不显示图标，即使progress为0")
    void emptySlot_shouldNotShowIdle() {
        assertEquals(OcImageSlotStatusEnum.EMPTY, resolver.resolve(null, BigDecimal.ZERO, null));
        assertEquals(OcImageSlotStatusEnum.EMPTY, resolver.resolve(null, BigDecimal.valueOf(50), Boolean.FALSE));
    }

    @Test
    @DisplayName("progress为0返回空转")
    void zeroProgress_shouldReturnIdle() {
        assertEquals(OcImageSlotStatusEnum.IDLE, resolver.resolve(1L, BigDecimal.ZERO, null));
    }

    @Test
    @DisplayName("0与100之间返回准备中")
    void preparingProgress_shouldReturnPreparing() {
        assertEquals(OcImageSlotStatusEnum.PREPARING, resolver.resolve(1L, BigDecimal.valueOf(50), null));
    }

    @Test
    @DisplayName("progress为100返回准备完成")
    void fullProgress_shouldReturnReady() {
        assertEquals(OcImageSlotStatusEnum.READY, resolver.resolve(1L, BigDecimal.valueOf(100), null));
    }

    @Test
    @DisplayName("显式不可用道具返回缺少道具")
    void unavailableItem_shouldReturnMissingItem() {
        assertEquals(OcImageSlotStatusEnum.MISSING_ITEM,
                resolver.resolve(1L, BigDecimal.valueOf(50), Boolean.FALSE));
    }

    @Test
    @DisplayName("空转优先于缺少道具")
    void idleTakesPriorityOverMissingItem() {
        assertEquals(OcImageSlotStatusEnum.IDLE,
                resolver.resolve(1L, BigDecimal.ZERO, Boolean.FALSE));
    }

    @Test
    @DisplayName("缺少道具优先于准备完成")
    void missingItemTakesPriorityOverReady() {
        assertEquals(OcImageSlotStatusEnum.MISSING_ITEM,
                resolver.resolve(1L, BigDecimal.valueOf(100), Boolean.FALSE));
    }

    @Test
    @DisplayName("未知道具快照和非法进度不误报")
    void unknownOrIllegalProgress_shouldNotMisreport() {
        assertEquals(OcImageSlotStatusEnum.UNKNOWN, resolver.resolve(1L, null, null));
        assertEquals(OcImageSlotStatusEnum.UNKNOWN, resolver.resolve(1L, BigDecimal.valueOf(-1), null));
        assertEquals(OcImageSlotStatusEnum.UNKNOWN, resolver.resolve(1L, BigDecimal.valueOf(101), null));
        assertEquals(OcImageSlotStatusEnum.PREPARING, resolver.resolve(1L, BigDecimal.valueOf(50), null));
    }
}
