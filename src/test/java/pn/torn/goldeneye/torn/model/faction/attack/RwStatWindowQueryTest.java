package pn.torn.goldeneye.torn.model.faction.attack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RW统计窗口参数解析测试。
 *
 * @author Bai
 * @version 1.4.4
 * @since 2026.08.24
 */
@DisplayName("RW统计窗口参数解析测试")
class RwStatWindowQueryTest {
    @Test
    @DisplayName("空参数解析为当前RW全场查询")
    void parse_empty_returnsCurrentRwWithoutWindow() {
        RwStatWindowQuery query = RwStatWindowQuery.parse("");

        assertNull(query.rwId());
        assertNull(query.windowCode());
    }

    @Test
    @DisplayName("纯数字参数始终解析为RWID")
    void parse_numeric_resolvesRwId() {
        RwStatWindowQuery query = RwStatWindowQuery.parse("123");

        assertEquals(123L, query.rwId());
        assertNull(query.windowCode());
    }

    @Test
    @DisplayName("窗口字母和小写字母被正确解析")
    void parse_windowCode_normalizesToUpperCase() {
        RwStatWindowQuery query = RwStatWindowQuery.parse("aa");

        assertNull(query.rwId());
        assertEquals("AA", query.windowCode());
    }

    @Test
    @DisplayName("RWID和窗口字母组合参数被正确解析")
    void parse_rwIdAndWindowCode_resolvesBoth() {
        RwStatWindowQuery query = RwStatWindowQuery.parse("123#A");

        assertEquals(123L, query.rwId());
        assertEquals("A", query.windowCode());
    }

    @Test
    @DisplayName("all参数解析为查询所有窗口")
    void parse_all_resolvesAllWindows() {
        RwStatWindowQuery query = RwStatWindowQuery.parse("all");

        assertNull(query.rwId());
        assertEquals("ALL", query.windowCode());
        assertTrue(query.allWindows());
    }

    @Test
    @DisplayName("all大小写不敏感")
    void parse_all_caseInsensitive() {
        assertTrue(RwStatWindowQuery.parse("ALL").allWindows());
        assertTrue(RwStatWindowQuery.parse("All").allWindows());
    }

    @Test
    @DisplayName("RWID和all组合参数被正确解析")
    void parse_rwIdAndAll_resolvesBoth() {
        RwStatWindowQuery query = RwStatWindowQuery.parse("123#all");

        assertEquals(123L, query.rwId());
        assertEquals("ALL", query.windowCode());
        assertTrue(query.allWindows());
    }

    @Test
    @DisplayName("空片段、超量参数和混合字符被拒绝")
    void parse_invalidSegments_rejected() {
        assertThrows(IllegalArgumentException.class, () -> RwStatWindowQuery.parse("#A"));
        assertThrows(IllegalArgumentException.class, () -> RwStatWindowQuery.parse("123#"));
        assertThrows(IllegalArgumentException.class, () -> RwStatWindowQuery.parse("123#A#B"));
        assertThrows(IllegalArgumentException.class, () -> RwStatWindowQuery.parse("A1"));
        assertThrows(IllegalArgumentException.class, () -> RwStatWindowQuery.parse("1#2"));
        assertThrows(IllegalArgumentException.class, () -> RwStatWindowQuery.parse("0"));
    }
}
