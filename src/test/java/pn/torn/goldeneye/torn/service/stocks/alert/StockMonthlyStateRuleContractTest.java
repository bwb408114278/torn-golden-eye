package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 股票月度状态规则契约测试，锁定成熟度和确认入口的业务边界。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.17
 */
@DisplayName("股票月度状态规则契约测试")
class StockMonthlyStateRuleContractTest {

    private static final Path SOURCE_PATH = Path.of(
            "src/main/java/pn/torn/goldeneye/torn/service/stocks/alert/StockMonthlyStateInitService.java");

    @Test
    @DisplayName("月度状态_必须使用自然日成熟度边界")
    void monthlyState_usesNaturalDayMaturityBoundaries() throws Exception {
        String source = Files.readString(SOURCE_PATH, StandardCharsets.UTF_8);

        assertTrue(source.contains("MATURE_DAYS = 365"), "必须存在365天成熟度边界");
        assertTrue(source.contains("SEASONED_DAYS = 240"), "必须存在240天成熟度边界");
        assertTrue(source.contains("PROVISIONAL_DAYS = 120"), "必须存在120天成熟度边界");
        assertTrue(source.contains("EARLY_DAYS = 60"), "必须存在60天成熟度边界");
        assertFalse(source.contains("MATURE_BAR_THRESHOLD"), "不得继续使用bar数量作为成熟度边界");
    }

    @Test
    @DisplayName("月度状态_必须区分人工确认和系统确认入口")
    void monthlyState_separatesManualAndAutomaticConfirmation() throws Exception {
        String source = Files.readString(SOURCE_PATH, StandardCharsets.UTF_8);

        assertTrue(source.contains("confirmDraftStates(LocalDate effectiveMonth, String confirmedBy)"),
                "人工确认必须接收真实确认人");
        assertTrue(source.contains("autoConfirmDraftStates(LocalDate effectiveMonth)"),
                "系统确认必须使用独立入口");
        assertTrue(source.contains("confirmedBy.isBlank()"),
                "人工确认必须拒绝空白确认人");
    }
}
