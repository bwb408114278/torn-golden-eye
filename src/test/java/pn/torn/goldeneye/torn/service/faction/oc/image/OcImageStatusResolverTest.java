package pn.torn.goldeneye.torn.service.faction.oc.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.oc.image.OcImageSlotStatusEnum;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OC 表格图片槽位状态解析测试。
 * <p>
 * 验证空槽、空转、准备中、准备完成、道具ID与可用性联合判定、优先级和非法进度降级。
 *
 * @author Bai
 * @version 1.5.2
 * @since 2026.08.30
 */
@DisplayName("OC槽位状态解析测试")
class OcImageStatusResolverTest {

    private final OcImageStatusResolver resolver = new OcImageStatusResolver();

    @Test
    @DisplayName("空槽不显示图标，即使progress为0或道具快照不可用")
    void emptySlot_shouldNotShowIdle() {
        assertEquals(OcImageSlotStatusEnum.EMPTY, resolver.resolve(null, BigDecimal.ZERO, null, null));
        assertEquals(OcImageSlotStatusEnum.EMPTY, resolver.resolve(null, BigDecimal.valueOf(50), 100, Boolean.FALSE));
    }

    @Test
    @DisplayName("已加入且progress为0返回空转")
    void zeroProgress_shouldReturnIdle() {
        assertEquals(OcImageSlotStatusEnum.IDLE, resolver.resolve(1L, BigDecimal.ZERO, null, null));
    }

    @Test
    @DisplayName("已加入且0与100之间返回准备中")
    void preparingProgress_shouldReturnPreparing() {
        assertEquals(OcImageSlotStatusEnum.PREPARING, resolver.resolve(1L, BigDecimal.valueOf(50), null, null));
    }

    @Test
    @DisplayName("已加入且progress为100返回准备完成")
    void fullProgress_shouldReturnReady() {
        assertEquals(OcImageSlotStatusEnum.READY, resolver.resolve(1L, BigDecimal.valueOf(100), null, null));
    }

    @Test
    @DisplayName("道具ID非空且快照不可用返回缺少道具")
    void unavailableItemWithItemId_shouldReturnMissingItem() {
        assertEquals(OcImageSlotStatusEnum.MISSING_ITEM,
                resolver.resolve(1L, BigDecimal.valueOf(50), 100, Boolean.FALSE));
    }

    @Test
    @DisplayName("道具ID为null时即使快照不可用也不返回缺少道具")
    void nullItemIdWithUnavailableSnapshot_shouldNotReturnMissingItem() {
        assertEquals(OcImageSlotStatusEnum.PREPARING,
                resolver.resolve(1L, BigDecimal.valueOf(50), null, Boolean.FALSE));
        assertEquals(OcImageSlotStatusEnum.READY,
                resolver.resolve(1L, BigDecimal.valueOf(100), null, Boolean.FALSE));
    }

    @Test
    @DisplayName("道具ID非空但快照可用性未知不返回缺少道具")
    void nullAvailability_shouldNotReturnMissingItem() {
        assertEquals(OcImageSlotStatusEnum.PREPARING,
                resolver.resolve(1L, BigDecimal.valueOf(50), 100, null));
    }

    @Test
    @DisplayName("空转优先于缺少道具")
    void idleTakesPriorityOverMissingItem() {
        assertEquals(OcImageSlotStatusEnum.IDLE,
                resolver.resolve(1L, BigDecimal.ZERO, 100, Boolean.FALSE));
    }

    @Test
    @DisplayName("缺少道具优先于准备完成")
    void missingItemTakesPriorityOverReady() {
        assertEquals(OcImageSlotStatusEnum.MISSING_ITEM,
                resolver.resolve(1L, BigDecimal.valueOf(100), 100, Boolean.FALSE));
    }

    @Test
    @DisplayName("非法进度即使道具快照不可用也返回未知，不误报缺道具")
    void illegalProgressWithUnavailableSnapshot_shouldReturnUnknown() {
        assertEquals(OcImageSlotStatusEnum.UNKNOWN, resolver.resolve(1L, null, 100, Boolean.FALSE));
        assertEquals(OcImageSlotStatusEnum.UNKNOWN, resolver.resolve(1L, BigDecimal.valueOf(-1), 100, Boolean.FALSE));
        assertEquals(OcImageSlotStatusEnum.UNKNOWN, resolver.resolve(1L, BigDecimal.valueOf(101), 100, Boolean.FALSE));
    }

    @Test
    @DisplayName("每个状态只有唯一Emoji，空槽和未知状态不携带业务Emoji")
    void eachStatus_shouldHaveUniqueEmoji() {
        assertEquals("💤", OcImageSlotStatusEnum.IDLE.getEmoji());
        assertEquals("⏳", OcImageSlotStatusEnum.PREPARING.getEmoji());
        assertEquals("✅", OcImageSlotStatusEnum.READY.getEmoji());
        assertEquals("⚠️", OcImageSlotStatusEnum.MISSING_ITEM.getEmoji());
        assertEquals("", OcImageSlotStatusEnum.EMPTY.getEmoji());
        assertEquals("", OcImageSlotStatusEnum.UNKNOWN.getEmoji());
    }
}
