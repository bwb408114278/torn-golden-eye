package pn.torn.goldeneye.msg.send.param;

import lombok.Data;
import pn.torn.goldeneye.constants.bot.enums.GroupMsgTypeEnum;
import pn.torn.goldeneye.msg.send.data.AtMsgData;

/**
 * At群聊消息
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.06.22
 */
@Data
public class AtGroupMsg implements GroupMsgParam<AtMsgData> {
    /**
     * 类型
     */
    private final String type = GroupMsgTypeEnum.AT.getCode();
    /**
     * 消息
     */
    private final AtMsgData data;

    public AtGroupMsg(Long param) {
        this.data = new AtMsgData(param);
    }
}