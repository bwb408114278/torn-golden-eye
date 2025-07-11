package pn.torn.goldeneye.msg.receive;

import lombok.Data;

/**
 * 群聊接收消息 - 消息数据
 * 目前只做了文本消息
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.11
 */
@Data
public class GroupRecMsgData {
    /**
     * 文本消息
     */
    private String text;
}
