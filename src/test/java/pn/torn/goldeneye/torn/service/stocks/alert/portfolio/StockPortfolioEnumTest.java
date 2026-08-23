package pn.torn.goldeneye.torn.service.stocks.alert.portfolio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.*;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 股票组合相关枚举映射测试 - 验证全部16个枚举的中文展示与fromCode方法
 * <p>
 * 逐一遍历每个枚举的所有值,断言 chineseDisplay 非空且为纯中文(不含ASCII英文字母),
 * 验证 fromCode 对有效编码能正确还原枚举值、对无效编码抛出 IllegalArgumentException。
 * 同时验证 StockBatchStatusEnum.isActive() 与 StockMaturityEnum.isUsable() 的状态判定。
 *
 * @author Bai
 * @version 1.2.13
 * @since 2026.07.24
 */
@DisplayName("股票组合枚举映射测试")
class StockPortfolioEnumTest {

    /**
     * 匹配ASCII英文字母(a-zA-Z)的正则,用于校验chineseDisplay不含英文
     */
    private static final Pattern ASCII_LETTER = Pattern.compile("[a-zA-Z]");

    // ==================== chineseDisplay 非空且不含英文 ====================

    @Test
    @DisplayName("买入策略枚举_ 所有值chineseDisplay非空且不含英文")
    void stockBuyStrategyEnum_allValues_chineseDisplayNonEmptyAndNoEnglish() {
        for (StockBuyStrategyEnum e : StockBuyStrategyEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("批次状态枚举_ 所有值chineseDisplay非空且不含英文")
    void stockBatchStatusEnum_allValues_chineseDisplayNonEmptyAndNoEnglish() {
        for (StockBatchStatusEnum e : StockBatchStatusEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("关闭类型枚举_ 所有值chineseDisplay非空且不含英文")
    void stockCloseTypeEnum_allValues_chineseDisplayNonEmptyAndNoEnglish() {
        for (StockCloseTypeEnum e : StockCloseTypeEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("槽位状态枚举_ 所有值chineseDisplay非空且不含英文")
    void stockSlotStatusEnum_allValues_chineseDisplayNonEmptyAndNoEnglish() {
        for (StockSlotStatusEnum e : StockSlotStatusEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("账本类型枚举_ 所有值chineseDisplay非空且不含英文")
    void stockLedgerTypeEnum_allValues_chineseDisplayNonEmptyAndNoEnglish() {
        for (StockLedgerTypeEnum e : StockLedgerTypeEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("资格结果枚举_ 所有值chineseDisplay非空且不含英文")
    void stockEligibilityResultEnum_allValues_chineseDisplayNonEmptyAndNoEnglish() {
        for (StockEligibilityResultEnum e : StockEligibilityResultEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("组合决策枚举_ 所有值chineseDisplay非空且不含英文")
    void stockPortfolioDecisionEnum_allValues_chineseDisplayNonEmptyAndNoEnglish() {
        for (StockPortfolioDecisionEnum e : StockPortfolioDecisionEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("通知类型枚举_ 所有值chineseDisplay非空且不含英文")
    void stockNoticeTypeEnum_allValues_chineseDisplayNonEmptyAndNoEnglish() {
        for (StockNoticeTypeEnum e : StockNoticeTypeEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("通知状态枚举_ 所有值chineseDisplay非空且不含英文")
    void stockNoticeStatusEnum_allValues_chineseDisplayNonEmptyAndNoEnglish() {
        for (StockNoticeStatusEnum e : StockNoticeStatusEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("取消原因枚举_ 所有值chineseDisplay非空且不含英文")
    void stockCancelReasonEnum_allValues_chineseDisplayNonEmptyAndNoEnglish() {
        for (StockCancelReasonEnum e : StockCancelReasonEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("规则模式枚举_ 所有值chineseDisplay非空且不含英文")
    void stockRuleModeEnum_allValues_chineseDisplayNonEmptyAndNoEnglish() {
        for (StockRuleModeEnum e : StockRuleModeEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("策略适配枚举_ 所有值chineseDisplay非空且不含英文")
    void stockStrategyFitEnum_allValues_chineseDisplayNonEmptyAndNoEnglish() {
        for (StockStrategyFitEnum e : StockStrategyFitEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("风险等级枚举_ 所有值chineseDisplay非空且不含英文")
    void stockRiskLevelEnum_allValues_chineseDisplayNonEmptyAndNoEnglish() {
        for (StockRiskLevelEnum e : StockRiskLevelEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("月度状态枚举_ 所有值chineseDisplay非空且不含英文")
    void stockMonthlyStateStatusEnum_allValues_chineseDisplayNonEmptyAndNoEnglish() {
        for (StockMonthlyStateStatusEnum e : StockMonthlyStateStatusEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(ASCII_LETTER.matcher(e.getChineseDisplay()).find(),
                    "chineseDisplay不应含英文: " + e + " -> " + e.getChineseDisplay());
        }
    }

    @Test
    @DisplayName("轮次状态枚举_ 所有值chineseDisplay非空且以中文为主")
    void stockRoundStatusEnum_allValues_chineseDisplayNonEmpty() {
        for (StockRoundStatusEnum e : StockRoundStatusEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            // BUILDING_BAR的chineseDisplay为"构建Bar中",含英文Bar属于业务术语,验证以中文开头
            assertTrue(e.getChineseDisplay().chars().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN),
                    "chineseDisplay应包含中文字符: " + e + " -> " + e.getChineseDisplay());
        }
    }

    // ==================== StockFormalReasonEnum 契约(P2-2) ====================

    @Test
    @DisplayName("正式原因枚举_ 编码唯一")
    void stockFormalReasonEnum_codesAreUnique() {
        long distinct = java.util.Arrays.stream(StockFormalReasonEnum.values())
                .map(StockFormalReasonEnum::getCode)
                .distinct()
                .count();
        assertEquals(StockFormalReasonEnum.values().length, distinct, "正式原因编码必须唯一");
    }

    @Test
    @DisplayName("正式原因枚举_ chineseDisplay非空")
    void stockFormalReasonEnum_chineseDisplayNonEmpty() {
        for (StockFormalReasonEnum e : StockFormalReasonEnum.values()) {
            assertNotNull(e.getChineseDisplay(), "chineseDisplay不应为空: " + e);
            assertFalse(e.getChineseDisplay().isBlank(), "chineseDisplay不应为空白: " + e);
        }
    }

    @Test
    @DisplayName("正式原因枚举_ fromCode可逆且非法值拒绝")
    void stockFormalReasonEnum_fromCodeReversibleAndInvalidRejected() {
        for (StockFormalReasonEnum e : StockFormalReasonEnum.values()) {
            assertEquals(e, StockFormalReasonEnum.fromCode(e.getCode()), "fromCode(getCode())应可逆: " + e);
        }
        assertThrows(IllegalArgumentException.class,
                () -> StockFormalReasonEnum.fromCode("NON_EXISTENT_CODE"),
                "非法正式原因编码必须拒绝");
    }

    @Test
    @DisplayName("正式原因枚举_ 冻结集合包含当前引擎实际产出的全部编码")
    void stockFormalReasonEnum_frozenSetContainsEngineOutputs() {
        java.util.Set<String> engineOutputs = java.util.Set.of(
                "HOLD_NO_EXIT_TRIGGERED",
                "SELL_TARGET_REACHED",
                "SELL_RANGE_RECOVERED",
                "SELL_HARD_RISK",
                "SELL_MAX_HOLD",
                "SELL_DATA_ADMIN_CLOSE",
                "EXIT_RANGE_FEATURE_MISSING"
        );
        java.util.Set<String> enumCodes = java.util.Arrays.stream(StockFormalReasonEnum.values())
                .map(StockFormalReasonEnum::getCode)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(enumCodes.containsAll(engineOutputs),
                "冻结正式原因集合必须包含当前引擎实际产出, 缺失: "
                        + engineOutputs.stream().filter(c -> !enumCodes.contains(c)).toList());
    }

    // ==================== StockBatchStatusEnum.isActive ====================

    @Test
    @DisplayName("批次状态枚举_ isActive对活跃状态返回true")
    void stockBatchStatusEnum_isActive_activeStatusReturnsTrue() {
        assertTrue(StockBatchStatusEnum.ENTRY_PENDING.isActive(), "ENTRY_PENDING应为活跃");
        assertTrue(StockBatchStatusEnum.OPEN.isActive(), "OPEN应为活跃");
        assertTrue(StockBatchStatusEnum.DATA_STALE.isActive(), "DATA_STALE应为活跃");
        assertTrue(StockBatchStatusEnum.EXIT_PENDING.isActive(), "EXIT_PENDING应为活跃");
        assertTrue(StockBatchStatusEnum.DATA_STALE_EXIT.isActive(), "DATA_STALE_EXIT应为活跃");
    }

    @Test
    @DisplayName("批次状态枚举_ isActive对关闭状态返回false")
    void stockBatchStatusEnum_isActive_closedStatusReturnsFalse() {
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
    @DisplayName("成熟度枚举_ isUsable对M2/M3/M4返回true")
    void stockMaturityEnum_isUsable_M2M3M4ReturnsTrue() {
        assertTrue(StockMaturityEnum.M2_PROVISIONAL.isUsable(), "M2暂定应可用");
        assertTrue(StockMaturityEnum.M3_SEASONED.isUsable(), "M3较成熟应可用");
        assertTrue(StockMaturityEnum.M4_MATURE.isUsable(), "M4成熟应可用");
    }

    @Test
    @DisplayName("成熟度枚举_ isUsable对M0/M1返回false")
    void stockMaturityEnum_isUsable_M0M1ReturnsFalse() {
        assertFalse(StockMaturityEnum.M0_UNMATURE.isUsable(), "M0未成熟应不可用");
        assertFalse(StockMaturityEnum.M1_EARLY.isUsable(), "M1早期应不可用");
    }

    // ==================== fromCode 有效编码 ====================

    @Test
    @DisplayName("编码转换_ 有效编码返回对应枚举(覆盖全部16个枚举)")
    void fromCode_validCode_returnsCorrespondingEnum() {
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
    @DisplayName("编码转换_ 无效编码抛出IllegalArgumentException(覆盖全部16个枚举)")
    void fromCode_invalidCode_throwsIllegalArgumentException() {
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
