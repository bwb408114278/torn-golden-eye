package pn.torn.goldeneye.napcat.receive.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.napcat.receive.msg.GroupRecMsgData;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsg;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgDetail;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NapCat 入站命令解析器测试。
 *
 * @author Bai
 * @version 1.4.0
 * @since 2026.08.21
 */
@DisplayName("QqCommandMessageParser 测试")
class QqCommandMessageParserTest {

    @Test
    @DisplayName("单文本命令成功解析")
    void parse_singleTextCommand_success() {
        QqRecMsg msg = message(text("g#战力增长#12345"));

        QqCommandMessage result = QqCommandMessageParser.parse(msg);

        assertEquals("g#战力增长#12345", result.commandText());
        assertEquals("", result.atMarker());
        assertFalse(result.hasAt());
    }

    @Test
    @DisplayName("text + at + text(空格) 成功解析且 at 后空格不作为参数")
    void parse_textAtTrailingSpace_success() {
        QqRecMsg msg = message(text("g#战力增长#"), at("408114278"), text(" "));

        QqCommandMessage result = QqCommandMessageParser.parse(msg);

        assertEquals("g#战力增长#", result.commandText());
        assertEquals(QqCommandMessage.buildAtMarker(408114278L), result.atMarker());
        assertTrue(result.hasAt());
    }

    @Test
    @DisplayName("data.qq 为字符串时成功解析")
    void parse_atQqAsString_success() {
        QqRecMsg msg = message(text("g#OC成功率#"), at("12345"));

        QqCommandMessage result = QqCommandMessageParser.parse(msg);

        assertEquals("g#OC成功率#", result.commandText());
        assertEquals(QqCommandMessage.buildAtMarker(12345L), result.atMarker());
    }

    @Test
    @DisplayName("多个 at 返回非法 at 标记")
    void parse_multipleAt_invalidMarker() {
        QqRecMsg msg = message(text("g#战力增长#"), at("12345"), at("67890"));

        QqCommandMessage result = QqCommandMessageParser.parse(msg);

        assertEquals("g#战力增长#", result.commandText());
        assertEquals(QqCommandMessage.INVALID_AT_MARKER, result.atMarker());
    }

    @Test
    @DisplayName("非法 QQ 返回非法 at 标记")
    void parse_illegalAt_invalidMarker() {
        QqRecMsg msg = message(text("g#战力增长#"), at("abc"));

        QqCommandMessage result = QqCommandMessageParser.parse(msg);

        assertEquals("g#战力增长#", result.commandText());
        assertEquals(QqCommandMessage.INVALID_AT_MARKER, result.atMarker());
    }

    @Test
    @DisplayName("只有 at、没有有效 g# 文本时不识别为命令")
    void parse_onlyAt_notCommand() {
        QqRecMsg msg = message(at("12345"));

        assertNull(QqCommandMessageParser.parse(msg));
    }

    @Test
    @DisplayName("混入图片等非 text/at 段时不识别为命令")
    void parse_mixedImage_notCommand() {
        QqRecMsg msg = message(text("g#战力增长#"), at("12345"), image());

        assertNull(QqCommandMessageParser.parse(msg));
    }

    @Test
    @DisplayName("text 段包含内部标记控制字符时拒绝，防止普通文本伪造 at")
    void parse_textContainsInternalMarker_rejected() {
        QqRecMsg msg = message(text("g#战力增长#" + QqCommandMessage.AT_MARKER_PREFIX + "12345"));

        assertNull(QqCommandMessageParser.parse(msg));
    }

    @Test
    @DisplayName("无 at 的多个 text 段保持原有非命令行为")
    void parse_multipleTextWithoutAt_notCommand() {
        QqRecMsg msg = message(text("g#战力增长#"), text("12345"));

        assertNull(QqCommandMessageParser.parse(msg));
    }

    private QqRecMsg message(QqRecMsgDetail... details) {
        QqRecMsg msg = new QqRecMsg();
        msg.setMessage(List.of(details));
        return msg;
    }

    private QqRecMsgDetail text(String text) {
        GroupRecMsgData data = new GroupRecMsgData();
        data.setText(text);
        return detail("text", data);
    }

    private QqRecMsgDetail at(String qq) {
        GroupRecMsgData data = new GroupRecMsgData();
        data.setQq(qq);
        return detail("at", data);
    }

    private QqRecMsgDetail image() {
        return detail("image", new GroupRecMsgData());
    }

    private QqRecMsgDetail detail(String type, GroupRecMsgData data) {
        QqRecMsgDetail detail = new QqRecMsgDetail();
        detail.setType(type);
        detail.setData(data);
        return detail;
    }
}
