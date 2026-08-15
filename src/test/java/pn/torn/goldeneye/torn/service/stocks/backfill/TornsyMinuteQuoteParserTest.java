package pn.torn.goldeneye.torn.service.stocks.backfill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tornsy m1 分钟报价解析器单元测试 - 覆盖 m1 3/4 元数组、可选市值、非法数组、非数值、
 * 非整分钟、非正价格/总股数、窗口外点与同分钟冲突等行级校验
 * <p>
 * 验证 {@link TornsyMinuteQuoteParser} 只保留合法行、非法行拒绝且不补值，
 * 未知市值返回 {@code null}（不以 0 代表未知）。
 *
 * @author Bai
 * @version 1.2.15
 * @since 2026.08.13
 */
@DisplayName("Tornsy m1 分钟报价解析器测试")
class TornsyMinuteQuoteParserTest {

    private final TornsyMinuteQuoteParser parser = new TornsyMinuteQuoteParser();

    private static final long MINUTE_EPOCH = 1786520040L;

    private LocalDateTime requestStart;
    private LocalDateTime requestEnd;
    private LocalDateTime stableEnd;

    private void window() {
        LocalDateTime minute = TornsyMinuteQuoteParser.toMinuteTime(MINUTE_EPOCH);
        requestStart = minute.minusMinutes(1);
        requestEnd = minute.plusMinutes(2);
        stableEnd = minute.plusHours(1);
    }

    @Test
    @DisplayName("解析_3元数组且价格字符串 -> 解析成功且市值为空")
    void parse_threeElementArrayWithStringPrice_parsesSuccessfully() {
        window();
        JsonNode row = row(MINUTE_EPOCH, "362.07", 15795177397L);

        List<TornsyMinuteQuote> quotes = parser.parse(List.of(row), requestStart, requestEnd, stableEnd);

        assertEquals(1, quotes.size());
        TornsyMinuteQuote quote = quotes.getFirst();
        assertEquals(TornsyMinuteQuoteParser.toMinuteTime(MINUTE_EPOCH), quote.minuteTime());
        assertEquals(0, new BigDecimal("362.07").compareTo(quote.price()));
        assertEquals(15795177397L, quote.totalShares());
        assertNull(quote.marketCap());
    }

    @Test
    @DisplayName("解析_4元数组含正市值 -> 解析成功且市值写入")
    void parse_fourElementArrayWithMarketCap_parsesMarketCap() {
        window();
        JsonNode row = row(MINUTE_EPOCH, "362.07", 15795177397L, 5000000000000L);

        List<TornsyMinuteQuote> quotes = parser.parse(List.of(row), requestStart, requestEnd, stableEnd);

        assertEquals(1, quotes.size());
        assertEquals(5000000000000L, quotes.getFirst().marketCap());
    }

    @Test
    @DisplayName("解析_4元数组市值null -> 解析成功且市值为空")
    void parse_fourElementArrayWithNullMarketCap_parsesNullMarketCap() {
        window();
        JsonNode row = rowWithNullMarketCap(MINUTE_EPOCH, "362.07", 15795177397L);

        List<TornsyMinuteQuote> quotes = parser.parse(List.of(row), requestStart, requestEnd, stableEnd);

        assertEquals(1, quotes.size());
        assertNull(quotes.getFirst().marketCap());
    }

    @Test
    @DisplayName("解析_非数组 -> 拒绝")
    void parse_nonArrayRow_rejects() {
        window();
        JsonNode row = JsonNodeFactory.instance.textNode("not-an-array");

        List<TornsyMinuteQuote> quotes = parser.parse(List.of(row), requestStart, requestEnd, stableEnd);

        assertTrue(quotes.isEmpty());
    }

    @Test
    @DisplayName("解析_数组长度错误 -> 拒绝")
    void parse_wrongLength_rejects() {
        window();
        JsonNode tooShort = JsonNodeFactory.instance.arrayNode().add(MINUTE_EPOCH).add("362.07");
        JsonNode tooLong = JsonNodeFactory.instance.arrayNode()
                .add(MINUTE_EPOCH).add("362.07").add(15795177397L).add(5000L).add(999L);

        List<TornsyMinuteQuote> quotes = parser.parse(List.of(tooShort, tooLong),
                requestStart, requestEnd, stableEnd);

        assertTrue(quotes.isEmpty());
    }

    @Test
    @DisplayName("解析_epoch非数值 -> 拒绝")
    void parse_nonNumericEpoch_rejects() {
        window();
        JsonNode row = JsonNodeFactory.instance.arrayNode().add("abc").add("362.07").add(15795177397L);

        List<TornsyMinuteQuote> quotes = parser.parse(List.of(row), requestStart, requestEnd, stableEnd);

        assertTrue(quotes.isEmpty());
    }

    @Test
    @DisplayName("解析_非整分钟epoch -> 拒绝")
    void parse_nonMinuteEpoch_rejects() {
        window();
        JsonNode row = row(MINUTE_EPOCH + 45L, "362.07", 15795177397L);

        List<TornsyMinuteQuote> quotes = parser.parse(List.of(row), requestStart, requestEnd, stableEnd);

        assertTrue(quotes.isEmpty());
    }

    @Test
    @DisplayName("解析_窗口外时间 -> 拒绝")
    void parse_outOfWindow_rejects() {
        window();
        JsonNode row = row(MINUTE_EPOCH + 120L, "362.07", 15795177397L);

        List<TornsyMinuteQuote> quotes = parser.parse(List.of(row), requestStart, requestEnd, stableEnd);

        assertTrue(quotes.isEmpty());
    }

    @Test
    @DisplayName("解析_不早于稳定截止 -> 拒绝")
    void parse_notBeforeStableEnd_rejects() {
        LocalDateTime minute = TornsyMinuteQuoteParser.toMinuteTime(MINUTE_EPOCH);
        JsonNode row = row(MINUTE_EPOCH, "362.07", 15795177397L);

        List<TornsyMinuteQuote> quotes = parser.parse(List.of(row), minute.minusMinutes(1),
                minute.plusMinutes(2), minute);

        assertTrue(quotes.isEmpty());
    }

    @Test
    @DisplayName("解析_非正价格 -> 拒绝")
    void parse_nonPositivePrice_rejects() {
        window();
        JsonNode zeroPrice = row(MINUTE_EPOCH, "0.00", 15795177397L);
        JsonNode negativePrice = row(MINUTE_EPOCH, "-1.00", 15795177397L);

        List<TornsyMinuteQuote> quotes = parser.parse(List.of(zeroPrice, negativePrice),
                requestStart, requestEnd, stableEnd);

        assertTrue(quotes.isEmpty());
    }

    @Test
    @DisplayName("解析_非正总股数 -> 拒绝")
    void parse_nonPositiveTotalShares_rejects() {
        window();
        JsonNode row = row(MINUTE_EPOCH, "362.07", 0L);

        List<TornsyMinuteQuote> quotes = parser.parse(List.of(row), requestStart, requestEnd, stableEnd);

        assertTrue(quotes.isEmpty());
    }

    @Test
    @DisplayName("解析_可选市值非正 -> 拒绝")
    void parse_nonPositiveMarketCap_rejects() {
        window();
        JsonNode row = row(MINUTE_EPOCH, "362.07", 15795177397L, -5L);

        List<TornsyMinuteQuote> quotes = parser.parse(List.of(row), requestStart, requestEnd, stableEnd);

        assertTrue(quotes.isEmpty());
    }

    @Test
    @DisplayName("解析_价格非数值字符串 -> 拒绝")
    void parse_nonNumericPrice_rejects() {
        window();
        JsonNode row = row(MINUTE_EPOCH, "abc", 15795177397L);

        List<TornsyMinuteQuote> quotes = parser.parse(List.of(row), requestStart, requestEnd, stableEnd);

        assertTrue(quotes.isEmpty());
    }

    @Test
    @DisplayName("解析_同分钟价格冲突 -> 拒绝冲突行")
    void parse_sameMinuteConflict_rejectsConflictingRow() {
        window();
        JsonNode first = row(MINUTE_EPOCH, "362.07", 15795177397L);
        JsonNode conflict = row(MINUTE_EPOCH, "999.99", 15795177397L);

        List<TornsyMinuteQuote> quotes = parser.parse(List.of(first, conflict),
                requestStart, requestEnd, stableEnd);

        assertEquals(1, quotes.size());
        assertEquals(0, new BigDecimal("362.07").compareTo(quotes.getFirst().price()));
    }

    @Test
    @DisplayName("解析_同分钟值相同 -> 去重保留一条")
    void parse_sameMinuteSameValue_dedups() {
        window();
        JsonNode first = row(MINUTE_EPOCH, "362.07", 15795177397L);
        JsonNode duplicate = row(MINUTE_EPOCH, "362.07", 15795177397L);

        List<TornsyMinuteQuote> quotes = parser.parse(List.of(first, duplicate),
                requestStart, requestEnd, stableEnd);

        assertEquals(1, quotes.size());
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建三元 m1 行
     */
    private static JsonNode row(long epochSecond, String price, long totalShares) {
        return JsonNodeFactory.instance.arrayNode().add(epochSecond).add(price).add(totalShares);
    }

    /**
     * 构建四元 m1 行（含市值）
     */
    private static JsonNode row(long epochSecond, String price, long totalShares, long marketCap) {
        ArrayNode node = JsonNodeFactory.instance.arrayNode().add(epochSecond).add(price).add(totalShares);
        node.add(marketCap);
        return node;
    }

    /**
     * 构建四元 m1 行（市值为 null）
     */
    private static JsonNode rowWithNullMarketCap(long epochSecond, String price, long totalShares) {
        ArrayNode node = JsonNodeFactory.instance.arrayNode().add(epochSecond).add(price).add(totalShares);
        node.addNull();
        return node;
    }
}
