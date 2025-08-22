package pn.torn.goldeneye.msg.receive;

import lombok.Data;

/**
 * 群聊接收消息 - 消息详情
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.11
 */
@Data
public class QqRecMsgDetail {
    /**
     * 消息类型
     */
    private String type;
    /**
     * 消息数据
     */
    private GroupRecMsgData data;
}