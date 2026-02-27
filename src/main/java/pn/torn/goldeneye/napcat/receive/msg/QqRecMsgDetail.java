package pn.torn.goldeneye.napcat.receive.msg;

import lombok.Data;
import pn.torn.goldeneye.constants.bot.enums.GroupMsgTypeEnum;
import pn.torn.goldeneye.napcat.send.msg.param.AtQqMsg;
import pn.torn.goldeneye.napcat.send.msg.param.ImageQqMsg;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.utils.NumberUtils;

/**
 * 群聊接收消息 - 消息详情
 *
 * @author Bai
 * @version 0.5.0
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

    public QqMsgParam<?> convertToParam() {
        GroupMsgTypeEnum msgType = GroupMsgTypeEnum.codeOf(this.type);
        switch (msgType) {
            case TEXT -> {
                return new TextQqMsg(data.getText());
            }
            case AT -> {
                return NumberUtils.isLong(data.getQq()) ? new AtQqMsg(Long.parseLong(data.getQq())) : new TextQqMsg("");
            }
            case IMAGE -> {
                return ImageQqMsg.fromUrl(data.getUrl());
            }
            case null, default -> {
                return new TextQqMsg("");
            }
        }
    }
}