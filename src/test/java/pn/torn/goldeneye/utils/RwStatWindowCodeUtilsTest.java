package pn.torn.goldeneye.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RW对冲窗口字母编码工具测试。
 *
 * @author Bai
 * @version 1.4.4
 * @since 2026.08.24
 */
@DisplayName("RW对冲窗口字母编码工具测试")
class RwStatWindowCodeUtilsTest {
    @Test
    @DisplayName("正整数序号转换为窗口编码")
    void toCode_positiveSequence_convertsToExpectedCode() {
        assertEquals("A", RwStatWindowCodeUtils.toCode(1));
        assertEquals("Z", RwStatWindowCodeUtils.toCode(26));
        assertEquals("AA", RwStatWindowCodeUtils.toCode(27));
        assertEquals("AZ", RwStatWindowCodeUtils.toCode(52));
        assertEquals("BA", RwStatWindowCodeUtils.toCode(53));
    }

    @Test
    @DisplayName("窗口编码转换为正整数序号")
    void toSequence_code_convertsToExpectedSequence() {
        assertEquals(1, RwStatWindowCodeUtils.toSequence("A"));
        assertEquals(26, RwStatWindowCodeUtils.toSequence("Z"));
        assertEquals(27, RwStatWindowCodeUtils.toSequence("aa"));
        assertEquals(52, RwStatWindowCodeUtils.toSequence("AZ"));
        assertEquals(53, RwStatWindowCodeUtils.toSequence("BA"));
    }

    @Test
    @DisplayName("非法非正序号被拒绝")
    void toCode_nonPositiveSequence_rejected() {
        assertThrows(IllegalArgumentException.class, () -> RwStatWindowCodeUtils.toCode(0));
        assertThrows(IllegalArgumentException.class, () -> RwStatWindowCodeUtils.toCode(-1));
    }
}
