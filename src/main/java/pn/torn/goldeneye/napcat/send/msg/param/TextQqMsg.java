package pn.torn.goldeneye.napcat.send.msg.param;

import lombok.Data;
import pn.torn.goldeneye.constants.bot.enums.GroupMsgTypeEnum;
import pn.torn.goldeneye.napcat.send.msg.data.TextMsgData;

/**
 * 文本群聊消息
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.06.22
 */
@Data
public class TextQqMsg implements QqMsgParam<TextMsgData> {
    /**
     * 类型
     */
    private final String type = GroupMsgTypeEnum.TEXT.getCode();
    /**
     * 消息
     */
    private final TextMsgData data;

    public TextQqMsg(String param) {
        this.data = new TextMsgData(param);
    }
}