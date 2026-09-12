package pn.torn.goldeneye.torn.service.stocks.alert.notice;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.utils.JsonUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * 股票通知载荷读取工具 - 统一从通知审计payload快照读取冻结文本、冻结时间与业务字段。
 * <p>
 * 通知的发送前冻结口径只允许一套实现:已冻结判定要求payload快照同时存在非空的{@code messageText}
 * 与{@code frozenAt},只存在messageText而缺少frozenAt时判定为尚未冻结,必须重新冻结。
 * 普通通知与α换仓关联组共用本工具读取载荷,禁止各处自行解析JSON或各自定义冻结判定。
 * <p>
 * 所有读取方法都对null与空文本安全:快照为空、字段缺失、字段为null或字段为空白文本时统一返回null,
 * 由调用方决定fail-closed还是重新冻结。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.12
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.NONE)
public final class StockNoticePayloadReader {
    /**
     * 最终消息文本字段名。
     */
    private static final String FIELD_MESSAGE_TEXT = "messageText";
    /**
     * 冻结时间字段名。
     */
    private static final String FIELD_FROZEN_AT = "frozenAt";

    /**
     * 判断通知是否已完成首次payload冻结。
     * <p>
     * 冻结完成的标志是payload快照同时存在有效{@code messageText}与{@code frozenAt};
     * 仅存在messageText而缺少frozenAt时不算已冻结,必须重新冻结。
     *
     * @param notice 通知审计DO
     * @return 已冻结返回true;否则false
     */
    public static boolean isAlreadyFrozen(TornStockNoticeAuditDO notice) {
        return notice != null
                && readText(notice.getPayloadSnapshot(), FIELD_MESSAGE_TEXT) != null
                && readText(notice.getPayloadSnapshot(), FIELD_FROZEN_AT) != null;
    }

    /**
     * 从通知载荷快照中读取已冻结的最终消息文本。
     *
     * @param payloadSnapshot 通知载荷JSON
     * @return 冻结文本;不存在或为空时返回null
     */
    public static String readFrozenMessageText(String payloadSnapshot) {
        return readText(payloadSnapshot, FIELD_MESSAGE_TEXT);
    }

    /**
     * 读取通知载荷中固化的消息正文。
     * <p>
     * 与 {@link #readFrozenMessageText(String)} 的区别是不要求存在{@code frozenAt}:
     * 无关联批次的自包含通知(如每日摘要)在创建时即固化正文,崩溃恢复后可按原正文补冻结;
     * 正文缺失属于不可解释状态,由调用方fail-closed。
     *
     * @param notice 通知审计DO
     * @return 固化的消息正文;不存在或为空时返回null
     */
    public static String readMessageText(TornStockNoticeAuditDO notice) {
        return notice == null ? null : readText(notice.getPayloadSnapshot(), FIELD_MESSAGE_TEXT);
    }

    /**
     * 读取已冻结腿的冻结时间。
     *
     * @param payloadSnapshot 通知载荷JSON
     * @return 冻结时间;缺失或无法解析时返回null
     */
    public static LocalDateTime readFrozenAt(String payloadSnapshot) {
        String frozenAtText = readText(payloadSnapshot, FIELD_FROZEN_AT);
        if (frozenAtText == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(frozenAtText);
        } catch (DateTimeParseException e) {
            log.error("股票通知payload-冻结时间无法解析: frozenAt={}", frozenAtText);
            return null;
        }
    }

    /**
     * 读取通知payload快照中的文本字段。
     *
     * @param notice 通知审计DO
     * @param field  字段名
     * @return 字段文本;通知为空、字段不存在或为空时返回null
     */
    public static String readText(TornStockNoticeAuditDO notice, String field) {
        return notice == null ? null : readText(notice.getPayloadSnapshot(), field);
    }

    /**
     * 读取通知payload快照中的整数字段。
     *
     * @param notice 通知审计DO
     * @param field  字段名
     * @return 字段整数;通知为空、字段不存在时返回null
     */
    public static Integer readInt(TornStockNoticeAuditDO notice, String field) {
        JsonNode node = readNode(notice == null ? null : notice.getPayloadSnapshot(), field);
        return node == null ? null : node.asInt();
    }

    /**
     * 读取payload JSON中的文本字段。
     *
     * @param payloadSnapshot 通知载荷JSON
     * @param field           字段名
     * @return 字段文本;不存在或为空时返回null
     */
    private static String readText(String payloadSnapshot, String field) {
        JsonNode node = readNode(payloadSnapshot, field);
        if (node == null) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 解析payload JSON中的字段节点。
     *
     * @param payloadSnapshot 通知载荷JSON
     * @param field           字段名
     * @return 字段节点;快照为空、字段缺失或为null时返回null
     */
    private static JsonNode readNode(String payloadSnapshot, String field) {
        if (payloadSnapshot == null || payloadSnapshot.isBlank()) {
            return null;
        }
        JsonNode node = JsonUtils.getNode(payloadSnapshot, field);
        return node == null || node.isNull() ? null : node;
    }
}
