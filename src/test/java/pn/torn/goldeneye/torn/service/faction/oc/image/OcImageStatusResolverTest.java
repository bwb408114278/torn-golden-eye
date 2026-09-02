package pn.torn.goldeneye.torn.service.faction.oc.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.oc.image.OcImageSlotStatusEnum;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OC图片槽位状态解析测试。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
@DisplayName("OC图片槽位状态解析测试")
class OcImageStatusResolverTest {
    private final OcImageStatusResolver resolver = new OcImageStatusResolver();

    @Test
    @DisplayName("空槽不显示状态，空转优先于缺道具")
    void resolve_shouldPrioritizeEmptyAndIdle() {
        assertEquals(OcImageSlotStatusEnum.EMPTY,
                resolver.resolve(null, BigDecimal.ZERO, 100, Boolean.FALSE));
        assertEquals(OcImageSlotStatusEnum.IDLE,
                resolver.resolve(1L, BigDecimal.ZERO, 100, Boolean.FALSE));
    }

    @Test
    @DisplayName("明确不可用道具优先于完成状态")
    void resolve_shouldPrioritizeMissingItem() {
        assertEquals(OcImageSlotStatusEnum.MISSING_ITEM,
                resolver.resolve(1L, BigDecimal.valueOf(100), 100, Boolean.FALSE));
        assertEquals(OcImageSlotStatusEnum.READY,
                resolver.resolve(1L, BigDecimal.valueOf(100), 100, Boolean.TRUE));
    }

    @Test
    @DisplayName("准备中、未知进度和未知道具快照不误报")
    void resolve_shouldHandlePreparingAndUnknownSnapshots() {
        assertEquals(OcImageSlotStatusEnum.PREPARING,
                resolver.resolve(1L, BigDecimal.valueOf(50), 100, null));
        assertEquals(OcImageSlotStatusEnum.PREPARING,
                resolver.resolve(1L, BigDecimal.valueOf(50), null, Boolean.FALSE));
        assertEquals(OcImageSlotStatusEnum.UNKNOWN,
                resolver.resolve(1L, null, 100, Boolean.FALSE));
        assertEquals(OcImageSlotStatusEnum.UNKNOWN,
                resolver.resolve(1L, BigDecimal.valueOf(101), 100, Boolean.FALSE));
    }
}
