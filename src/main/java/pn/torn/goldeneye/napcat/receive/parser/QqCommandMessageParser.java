package pn.torn.goldeneye.napcat.receive.parser;

import pn.torn.goldeneye.napcat.receive.msg.GroupRecMsgData;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsg;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgDetail;

/**
 * NapCat 入站消息命令解析器。
 *
 * <p>将结构化消息段转换为纯文本命令和内部 at 目标标记。解析只依赖消息段类型与
 * {@code data.qq}，不解析 CQ 码，不发起 NapCat API，也不感知具体业务策略。</p>
 *
 * @author Bai
 * @version 1.4.0
 * @since 2026.08.21
 */
public final class QqCommandMessageParser {

    private QqCommandMessageParser() {
    }

    /**
     * 解析入站消息。
     *
     * @param msg NapCat 入站消息
     * @return 解析结果；不是命令或包含非 text/at 段时返回 {@code null}
     */
    public static QqCommandMessage parse(QqRecMsg msg) {
        if (msg == null || msg.getMessage() == null || msg.getMessage().isEmpty()) {
            return null;
        }

        ParsingState state = new ParsingState();
        for (QqRecMsgDetail detail : msg.getMessage()) {
            if (!processDetail(state, detail)) {
                return null;
            }
        }
        return buildResult(state, msg.getMessage().size());
    }

    /**
     * 处理单个消息段。
     *
     * @param state  解析状态
     * @param detail 消息段
     * @return false 表示该消息段导致命令不成立
     */
    private static boolean processDetail(ParsingState state, QqRecMsgDetail detail) {
        if (detail == null) {
            return false;
        }
        String type = detail.getType();
        if ("text".equals(type)) {
            return appendText(state, detail.getData());
        }
        if ("at".equals(type)) {
            processAt(state, detail.getData());
            return true;
        }
        return false;
    }

    /**
     * 拼接 text 段并校验是否包含内部标记控制字符。
     *
     * @param state 解析状态
     * @param data  消息段数据
     * @return false 表示 text 段包含内部标记，命令不成立
     */
    private static boolean appendText(ParsingState state, GroupRecMsgData data) {
        String text = data == null ? "" : data.getText();
        if (text == null) {
            text = "";
        }
        if (containsInternalMarker(text)) {
            return false;
        }
        state.commandText.append(text);
        return true;
    }

    /**
     * 处理 at 段：记录数量、校验 QQ，并标记非法情况。
     *
     * @param state 解析状态
     * @param data  消息段数据
     */
    private static void processAt(ParsingState state, GroupRecMsgData data) {
        state.atCount++;
        if (state.atCount == 1 && !state.invalidAt) {
            String qq = data == null ? null : data.getQq();
            if (isPositiveLong(qq)) {
                state.validAtQq = Long.parseLong(qq);
                return;
            }
        }
        state.invalidAt = true;
    }

    /**
     * 根据解析状态构建最终命令对象。
     *
     * @param state       解析状态
     * @param segmentSize 原始消息段数量
     * @return 命令对象；不满足命令条件时返回 {@code null}
     */
    private static QqCommandMessage buildResult(ParsingState state, int segmentSize) {
        if (state.atCount == 0 && segmentSize != 1) {
            return null;
        }

        String text = state.atCount > 0 ? state.commandText.toString().trim() : state.commandText.toString();
        if (!text.startsWith("g#")) {
            return null;
        }

        return new QqCommandMessage(text, resolveAtMarker(state));
    }

    /**
     * 根据 at 数量与合法性生成内部标记。
     *
     * @param state 解析状态
     * @return 内部 at 标记；无 at 时为空字符串
     */
    private static String resolveAtMarker(ParsingState state) {
        if (state.invalidAt || state.atCount > 1) {
            return QqCommandMessage.INVALID_AT_MARKER;
        }
        if (state.atCount == 1 && state.validAtQq != null) {
            return QqCommandMessage.buildAtMarker(state.validAtQq);
        }
        return "";
    }

    /**
     * 解析过程中的可变状态。
     */
    private static class ParsingState {
        /**
         * 拼接后的纯文本命令。
         */
        private final StringBuilder commandText = new StringBuilder();
        /**
         * at 段数量。
         */
        private int atCount;
        /**
         * 唯一合法 at 对应的 QQ。
         */
        private Long validAtQq;
        /**
         * 是否存在非法 at。
         */
        private boolean invalidAt;
    }

    /**
     * 判断 QQ 是否为合法正整数且未超出 long 范围。
     *
     * @param qq at 段中的 QQ 字符串
     * @return true 表示合法正 long
     */
    private static boolean isPositiveLong(String qq) {
        if (qq == null || qq.isEmpty()) {
            return false;
        }
        try {
            return Long.parseLong(qq) > 0L;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 校验 text 段是否包含内部标记控制字符，防止用户通过普通文本伪造 at 标记。
     *
     * @param text 文本段内容
     * @return true 表示包含内部标记控制字符
     */
    private static boolean containsInternalMarker(String text) {
        return text.indexOf('\u0000') >= 0;
    }
}
