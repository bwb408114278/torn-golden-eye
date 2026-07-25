package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBuyStrategyEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockCancelReasonEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockCloseTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockEligibilityResultEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMonthlyStateStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockPortfolioDecisionEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRoundStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRuleModeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockSlotStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockStrategyFitEnum;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 股票组合相关枚举映射测试 - 验证全部16个枚举的中文展示与fromCode方法
 * <p>
 * 逐一遍历每个枚举的所有值,断言 chineseDisplay 非空且为纯中文(不含ASCII英文字母),
 * 验证 fromCode 对有效编码能正确还原枚举值、对无效编码抛出 IllegalArgumentException。
 * 同时验证 StockBatchStatusEnum.isActive() 与 StockMaturityEnum.isUsable() 的状态判定。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@DisplayName("股票组合枚举映射测试")
class StockPortfolioEnumTest {

    /** 匹配ASCII英文字母(a-zA-Z)的正则,用于校验chineseDisplay不含英文 */
    private static final Pattern ASCII_LETTER = Pattern.compile("[a-zA-Z]");

    // ==================== chineseDisplay 非空且不含英文 ====================

    @Test
    @DisplayName("StockBuyStrategyEnum: 所有值chineseDisplay非空且不含英文")
    void stockBuyStrategyEnum_所有值_chineseDisplay非空且不含英文() {
        for (StockBuyStrategyEnum e : StockBuyStrategyEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("StockBatchStatusEnum: 所有值chineseDisplay非空且不含英文")
    void stockBatchStatusEnum_所有值_chineseDisplay非空且不含英文() {
        for (StockBatchStatusEnum e : StockBatchStatusEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("StockCloseTypeEnum: 所有值chineseDisplay非空且不含英文")
    void stockCloseTypeEnum_所有值_chineseDisplay非空且不含英文() {
        for (StockCloseTypeEnum e : StockCloseTypeEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("StockSlotStatusEnum: 所有值chineseDisplay非空且不含英文")
    void stockSlotStatusEnum_所有值_chineseDisplay非空且不含英文() {
        for (StockSlotStatusEnum e : StockSlotStatusEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("StockLedgerTypeEnum: 所有值chineseDisplay非空且不含英文")
    void stockLedgerTypeEnum_所有值_chineseDisplay非空且不含英文() {
        for (StockLedgerTypeEnum e : StockLedgerTypeEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("StockEligibilityResultEnum: 所有值chineseDisplay非空且不含英文")
    void stockEligibilityResultEnum_所有值_chineseDisplay非空且不含英文() {
        for (StockEligibilityResultEnum e : StockEligibilityResultEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("StockPortfolioDecisionEnum: 所有值chineseDisplay非空且不含英文")
    void stockPortfolioDecisionEnum_所有值_chineseDisplay非空且不含英文() {
        for (StockPortfolioDecisionEnum e : StockPortfolioDecisionEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("StockNoticeTypeEnum: 所有值chineseDisplay非空且不含英文")
    void stockNoticeTypeEnum_所有值_chineseDisplay非空且不含英文() {
        for (StockNoticeTypeEnum e : StockNoticeTypeEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("StockNoticeStatusEnum: 所有值chineseDisplay非空且不含英文")
    void stockNoticeStatusEnum_所有值_chineseDisplay非空且不含英文() {
        for (StockNoticeStatusEnum e : StockNoticeStatusEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("StockCancelReasonEnum: 所有值chineseDisplay非空且不含英文")
    void stockCancelReasonEnum_所有值_chineseDisplay非空且不含英文() {
        for (StockCancelReasonEnum e : StockCancelReasonEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("StockRuleModeEnum: 所有值chineseDisplay非空且不含英文")
    void stockRuleModeEnum_所有值_chineseDisplay非空且不含英文() {
        for (StockRuleModeEnum e : StockRuleModeEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("StockStrategyFitEnum: 所有值chineseDisplay非空且不含英文")
    void stockStrategyFitEnum_所有值_chineseDisplay非空且不含英文() {
        for (StockStrategyFitEnum e : StockStrategyFitEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("StockRiskLevelEnum: 所有值chineseDisplay非空且不含英文")
    void stockRiskLevelEnum_所有值_chineseDisplay非空且不含英文() {
        for (StockRiskLevelEnum e : StockRiskLevelEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("StockMonthlyStateStatusEnum: 所有值chineseDisplay非空且不含英文")
    void stockMonthlyStateStatusEnum_所有值_chineseDisplay非空且不含英文() {
        for (StockMonthlyStateStatusEnum e : StockMonthlyStateStatusEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("StockRoundStatusEnum: 所有值chineseDisplay非空且以中文为主")
    void stockRoundStatusEnum_所有值_chineseDisplay非空() {
        for (StockRoundStatusEnum e : StockRoundStatusEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            // BUILDING_BAR的chineseDisplay为"构建Bar中",含英文Bar属于业务术语,验证以中文开头
            assertTrue(e.getChineseDisplay().chars().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN),
                    "chineseDisplay应包含中文字符: " + e + " -> " + e.getChineseDisplay());
        }
    }

    // ==================== StockBatchStatusEnum.isActive ====================

    @Test
    @DisplayName("StockBatchStatusEnum: isActive对活跃状态返回true")
    void stockBatchStatusEnum_isActive_活跃状态返回true() {
        assertTrue(StockBatchStatusEnum.ENTRY_PENDING.isActive(), "ENTRY_PENDING应为活跃");
        assertTrue(StockBatchStatusEnum.OPEN.isActive(), "OPEN应为活跃");
        assertTrue(StockBatchStatusEnum.DATA_STALE.isActive(), "DATA_STALE应为活跃");
        assertTrue(StockBatchStatusEnum.EXIT_PENDING.isActive(), "EXIT_PENDING应为活跃");
        assertTrue(StockBatchStatusEnum.DATA_STALE_EXIT.isActive(), "DATA_STALE_EXIT应为活跃");
    }

    @Test
    @DisplayName("StockBatchStatusEnum: isActive对关闭状态返回false")
    void stockBatchStatusEnum_isActive_关闭状态返回false() {
        assertFalse(StockBatchStatusEnum.CLOSED_TARGET.isActive(), "CLOSED_TARGET应非活跃");
        assertFalse(StockBatchStatusEnum.CLOSED_RANGE.isActive(), "CLOSED_RANGE应非活跃");
        assertFalse(StockBatchStatusEnum.CLOSED_RISK.isActive(), "CLOSED_RISK应非活跃");
        assertFalse(StockBatchStatusEnum.CLOSED_TIME.isActive(), "CLOSED_TIME应非活跃");
        assertFalse(StockBatchStatusEnum.CLOSED_DYNAMIC.isActive(), "CLOSED_DYNAMIC应非活跃");
        assertFalse(StockBatchStatusEnum.CLOSED_ROTATION.isActive(), "CLOSED_ROTATION应非活跃");
        assertFalse(StockBatchStatusEnum.ADMIN_CLOSED.isActive(), "ADMIN_CLOSED应非活跃");
        assertFalse(StockBatchStatusEnum.CANCELLED.isActive(), "CANCELLED应非活跃");
    }

    // ==================== StockMaturityEnum.isUsable ====================

    @Test
    @DisplayName("StockMaturityEnum: isUsable对M2/M3/M4返回true")
    void stockMaturityEnum_isUsable_M2M3M4返回true() {
        assertTrue(StockMaturityEnum.M2_PROVISIONAL.isUsable(), "M2暂定应可用");
        assertTrue(StockMaturityEnum.M3_SEASONED.isUsable(), "M3较成熟应可用");
        assertTrue(StockMaturityEnum.M4_MATURE.isUsable(), "M4成熟应可用");
    }

    @Test
    @DisplayName("StockMaturityEnum: isUsable对M0/M1返回false")
    void stockMaturityEnum_isUsable_M0M1返回false() {
        assertFalse(StockMaturityEnum.M0_UNMATURE.isUsable(), "M0未成熟应不可用");
        assertFalse(StockMaturityEnum.M1_EARLY.isUsable(), "M1早期应不可用");
    }

    // ==================== fromCode 有效编码 ====================

    @Test
    @DisplayName("fromCode: 有效编码返回对应枚举(覆盖全部16个枚举)")
    void fromCode_有效编码_返回对应枚举() {
        // 逐个枚举验证 fromCode 对每个值都能正确还原
        for (StockBuyStrategyEnum e : StockBuyStrategyEnum.values()) {
            assertEquals(e, StockBuyStrategyEnum.fromCode(e.getCode()));
        }
        for (StockBatchStatusEnum e : StockBatchStatusEnum.values()) {
            assertEquals(e, StockBatchStatusEnum.fromCode(e.getCode()));
        }
        for (StockCloseTypeEnum e : StockCloseTypeEnum.values()) {
            assertEquals(e, StockCloseTypeEnum.fromCode(e.getCode()));
        }
        for (StockSlotStatusEnum e : StockSlotStatusEnum.values()) {
            assertEquals(e, StockSlotStatusEnum.fromCode(e.getCode()));
        }
        for (StockLedgerTypeEnum e : StockLedgerTypeEnum.values()) {
            assertEquals(e, StockLedgerTypeEnum.fromCode(e.getCode()));
        }
        for (StockEligibilityResultEnum e : StockEligibilityResultEnum.values()) {
            assertEquals(e, StockEligibilityResultEnum.fromCode(e.getCode()));
        }
        for (StockPortfolioDecisionEnum e : StockPortfolioDecisionEnum.values()) {
            assertEquals(e, StockPortfolioDecisionEnum.fromCode(e.getCode()));
        }
        for (StockNoticeTypeEnum e : StockNoticeTypeEnum.values()) {
            assertEquals(e, StockNoticeTypeEnum.fromCode(e.getCode()));
        }
        for (StockNoticeStatusEnum e : StockNoticeStatusEnum.values()) {
            assertEquals(e, StockNoticeStatusEnum.fromCode(e.getCode()));
        }
        for (StockCancelReasonEnum e : StockCancelReasonEnum.values()) {
            assertEquals(e, StockCancelReasonEnum.fromCode(e.getCode()));
        }
        for (StockRuleModeEnum e : StockRuleModeEnum.values()) {
            assertEquals(e, StockRuleModeEnum.fromCode(e.getCode()));
        }
        for (StockStrategyFitEnum e : StockStrategyFitEnum.values()) {
            assertEquals(e, StockStrategyFitEnum.fromCode(e.getCode()));
        }
        for (StockMaturityEnum e : StockMaturityEnum.values()) {
            assertEquals(e, StockMaturityEnum.fromCode(e.getCode()));
        }
        for (StockRiskLevelEnum e : StockRiskLevelEnum.values()) {
            assertEquals(e, StockRiskLevelEnum.fromCode(e.getCode()));
        }
        for (StockMonthlyStateStatusEnum e : StockMonthlyStateStatusEnum.values()) {
            assertEquals(e, StockMonthlyStateStatusEnum.fromCode(e.getCode()));
        }
        for (StockRoundStatusEnum e : StockRoundStatusEnum.values()) {
            assertEquals(e, StockRoundStatusEnum.fromCode(e.getCode()));
        }
    }

    // ==================== fromCode 无效编码 ====================

    @Test
    @DisplayName("fromCode: 无效编码抛出IllegalArgumentException(覆盖全部16个枚举)")
    void fromCode_无效编码_抛出IllegalArgumentException() {
        String invalidCode = "NON_EXISTENT_CODE";

        assertThrows(IllegalArgumentException.class,
                () -> StockBuyStrategyEnum.fromCode(invalidCode));
        assertThrows(IllegalArgumentException.class,
                () -> StockBatchStatusEnum.fromCode(invalidCode));
        assertThrows(IllegalArgumentException.class,
                () -> StockCloseTypeEnum.fromCode(invalidCode));
        assertThrows(IllegalArgumentException.class,
                () -> StockSlotStatusEnum.fromCode(invalidCode));
        assertThrows(IllegalArgumentException.class,
                () -> StockLedgerTypeEnum.fromCode(invalidCode));
        assertThrows(IllegalArgumentException.class,
                () -> StockEligibilityResultEnum.fromCode(invalidCode));
        assertThrows(IllegalArgumentException.class,
                () -> StockPortfolioDecisionEnum.fromCode(invalidCode));
        assertThrows(IllegalArgumentException.class,
                () -> StockNoticeTypeEnum.fromCode(invalidCode));
        assertThrows(IllegalArgumentException.class,
                () -> StockNoticeStatusEnum.fromCode(invalidCode));
        assertThrows(IllegalArgumentException.class,
                () -> StockCancelReasonEnum.fromCode(invalidCode));
        assertThrows(IllegalArgumentException.class,
                () -> StockRuleModeEnum.fromCode(invalidCode));
        assertThrows(IllegalArgumentException.class,
                () -> StockStrategyFitEnum.fromCode(invalidCode));
        assertThrows(IllegalArgumentException.class,
                () -> StockMaturityEnum.fromCode(invalidCode));
        assertThrows(IllegalArgumentException.class,
                () -> StockRiskLevelEnum.fromCode(invalidCode));
        assertThrows(IllegalArgumentException.class,
                () -> StockMonthlyStateStatusEnum.fromCode(invalidCode));
        assertThrows(IllegalArgumentException.class,
                () -> StockRoundStatusEnum.fromCode(invalidCode));
    }
}
