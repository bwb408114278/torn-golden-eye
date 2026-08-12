package pn.torn.goldeneye.torn.service.stocks.alert.notice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 股票通知payload规范化工具测试,验证确定性键序、合并保留业务字段与哈希可复核。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.08.02
 */
@DisplayName("股票通知payload规范化工具测试")
class StockNoticePayloadCanonicalizerTest {

    @Test
    @DisplayName("对象键递归字典序排序_数组保序且无空白")
    void canonicalize_sortsObjectKeysRecursivelyKeepsArrayOrder() {
        String input = "{\"b\":2,\"a\":1,\"arr\":[3,1,2],\"nested\":{\"z\":\"9\",\"y\":\"8\"}}";
        String canonical = StockNoticePayloadCanonicalizer.canonicalize(input);
        assertEquals("{\"a\":1,\"arr\":[3,1,2],\"b\":2,\"nested\":{\"y\":\"8\",\"z\":\"9\"}}", canonical,
                "对象键应递归字典序排序,数组应保持原顺序,不应有空白");
    }

    @Test
    @DisplayName("键序不同的等价JSON_规范化后哈希一致")
    void sha256_sameContentDifferentKeyOrder_sameHash() {
        String jsonA = "{\"b\":2,\"a\":1}";
        String jsonB = "{\"a\":1,\"b\":2}";
        assertEquals(StockNoticePayloadCanonicalizer.sha256(jsonA),
                StockNoticePayloadCanonicalizer.sha256(jsonB),
                "内容相同键序不同应得到相同哈希,保证JSONB读回后可复核");
    }

    @Test
    @DisplayName("数值规范化_无科学计数法且尾零归一(JSONB往返稳定)")
    void canonicalize_numberSemantics_plainDecimalAndStripTrailingZeros() {
        assertEquals("{\"n\":100}", StockNoticePayloadCanonicalizer.canonicalize("{\"n\":1e+2}"),
                "指数必须展开为普通十进制,禁止输出科学计数法(否则JSONB读回改写导致hash不稳)");
        assertEquals("{\"n\":100000}", StockNoticePayloadCanonicalizer.canonicalize("{\"n\":1E+5}"));
        assertEquals("{\"n\":100000}", StockNoticePayloadCanonicalizer.canonicalize("{\"n\":1e5}"));
        assertEquals("{\"n\":1}", StockNoticePayloadCanonicalizer.canonicalize("{\"n\":1.00}"),
                "尾零按stripTrailingZeros归一,写入JSONB的是归一后文本,读回可复核");
        assertEquals("{\"n\":1}", StockNoticePayloadCanonicalizer.canonicalize("{\"n\":1.0}"));
        assertEquals("{\"n\":1}", StockNoticePayloadCanonicalizer.canonicalize("{\"n\":1e0}"));
        assertEquals("{\"n\":1.5}", StockNoticePayloadCanonicalizer.canonicalize("{\"n\":1.50}"));
        assertEquals("{\"n\":0.001}", StockNoticePayloadCanonicalizer.canonicalize("{\"n\":0.001}"));
        assertEquals("{\"n\":100000000000000000000}",
                StockNoticePayloadCanonicalizer.canonicalize("{\"n\":1e20}"),
                "大数必须输出普通十进制,不得使用指数");
    }

    @Test
    @DisplayName("JSONB等价数值_不同指数写法规范化后哈希一致")
    void sha256_equivalentNumbers_sameHash() {
        // 归一化文本为写入JSONB的唯一表示: 1e+2/1e2/1E+2/100 均归一为100,哈希必须相同;
        // 该唯一表示与JSONB读回文本(展开指数)再规范化结果一致,保证hash可复核。
        String[] equivalents = {"{\"n\":1e+2}", "{\"n\":1e2}", "{\"n\":1E+2}", "{\"n\":100}"};
        String expected = StockNoticePayloadCanonicalizer.sha256("{\"n\":100}");
        for (String json : equivalents) {
            assertEquals(expected, StockNoticePayloadCanonicalizer.sha256(json),
                    "JSONB等价数值应得到相同哈希: " + json);
        }
    }

    @Test
    @DisplayName("合并_保留全部业务字段并追加messageText与frozenAt")
    void mergeAndCanonicalize_preservesBusinessFieldsAndAppendsTextAndTime() {
        String original = "{\"noticeType\":\"SELL\",\"formalReason\":\"SELL_DATA_ADMIN_CLOSE\","
                + "\"originalExitReason\":\"CLOSED_TARGET\",\"expectedExitBarTime\":\"2026-07-24 16:15:00\"}";
        LocalDateTime frozenAt = LocalDateTime.of(2026, 7, 24, 17, 0);

        String merged = StockNoticePayloadCanonicalizer.mergeAndCanonicalize(
                original, "最终中文文本", frozenAt);

        assertTrue(merged.contains("\"formalReason\":\"SELL_DATA_ADMIN_CLOSE\""),
                "灾难关闭正式原因必须保留");
        assertTrue(merged.contains("\"originalExitReason\":\"CLOSED_TARGET\""),
                "原退出原因必须保留");
        assertTrue(merged.contains("\"expectedExitBarTime\":\"2026-07-24 16:15:00\""),
                "预期成交时间必须保留");
        assertTrue(merged.contains("\"messageText\":\"最终中文文本\""),
                "最终文本必须写入");
        assertTrue(merged.contains("\"frozenAt\":\"" + frozenAt + "\""),
                "冻结时间必须写入");
    }

    @Test
    @DisplayName("合并后哈希_基于最终完整payload计算")
    void mergeAndCanonicalize_hashMatchesMergedJson() {
        String original = "{\"noticeType\":\"SELL\",\"batchId\":1}";
        LocalDateTime frozenAt = LocalDateTime.of(2026, 7, 24, 17, 0);
        String merged = StockNoticePayloadCanonicalizer.mergeAndCanonicalize(original, "文本", frozenAt);

        assertEquals(StockNoticePayloadCanonicalizer.sha256(merged),
                StockNoticePayloadCanonicalizer.sha256(
                        "{\"batchId\":1,\"noticeType\":\"SELL\",\"messageText\":\"文本\",\"frozenAt\":\""
                                + frozenAt + "\"}"),
                "哈希应等于最终完整payload规范化后的SHA-256");
    }

    @Test
    @DisplayName("原payload为空_合并抛异常")
    void mergeAndCanonicalize_emptyOriginal_throws() {
        LocalDateTime frozenAt = LocalDateTime.now();
        assertThrows(IllegalStateException.class,
                () -> StockNoticePayloadCanonicalizer.mergeAndCanonicalize("", "文本", frozenAt));
    }

    @Test
    @DisplayName("原payload非JSON对象_合并抛异常")
    void mergeAndCanonicalize_nonObject_throws() {
        LocalDateTime frozenAt = LocalDateTime.now();
        assertThrows(IllegalStateException.class,
                () -> StockNoticePayloadCanonicalizer.mergeAndCanonicalize("[1,2,3]", "文本", frozenAt));
    }

    @Test
    @DisplayName("非法JSON_规范化抛异常")
    void canonicalize_invalidJson_throws() {
        assertThrows(IllegalStateException.class,
                () -> StockNoticePayloadCanonicalizer.canonicalize("not-json"));
    }
}
