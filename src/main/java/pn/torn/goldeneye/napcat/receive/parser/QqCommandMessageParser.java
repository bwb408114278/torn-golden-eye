package pn.torn.goldeneye.napcat.receive.parser;

import pn.torn.goldeneye.napcat.receive.msg.GroupRecMsgData;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsg;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgDetail;

/**
 * NapCat 入站消息命令解析器。
 *
 * <p>将结构化消息段转换为纯文本命令和内部 at 目标标记。解析只依赖消息段类型与
 * {@code data.qq}，不解析 CQ 码，不发起 NapCat API，也不感知具体业务策略。</p>
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

        StringBuilder commandText = new StringBuilder();
        String atMarker = "";
        int atCount = 0;
        Long validAtQq = null;
        boolean invalidAt = false;

        for (QqRecMsgDetail detail : msg.getMessage()) {
            if (detail == null) {
                return null;
            }
            String type = detail.getType();
            GroupRecMsgData data = detail.getData();
            if ("text".equals(type)) {
                String text = data == null ? "" : data.getText();
                if (text == null) {
                    text = "";
                }
                if (containsInternalMarker(text)) {
                    return null;
                }
                commandText.append(text);
            } else if ("at".equals(type)) {
                atCount++;
                if (atCount == 1 && !invalidAt) {
                    String qq = data == null ? null : data.getQq();
                    if (isPositiveLong(qq)) {
                        long qqLong = Long.parseLong(qq);
                        validAtQq = qqLong;
                        continue;
                    }
                }
                invalidAt = true;
            } else {
                return null;
            }
        }

        if (atCount == 0 && msg.getMessage().size() != 1) {
            return null;
        }

        String text = atCount > 0 ? commandText.toString().trim() : commandText.toString();
        if (!text.startsWith("g#")) {
            return null;
        }

        if (invalidAt || atCount > 1) {
            atMarker = QqCommandMessage.INVALID_AT_MARKER;
        } else if (atCount == 1 && validAtQq != null) {
            atMarker = QqCommandMessage.buildAtMarker(validAtQq);
        }

        return new QqCommandMessage(text, atMarker);
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
