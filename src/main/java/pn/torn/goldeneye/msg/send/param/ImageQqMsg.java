package pn.torn.goldeneye.msg.send.param;

import lombok.Data;
import pn.torn.goldeneye.constants.bot.enums.GroupMsgTypeEnum;
import pn.torn.goldeneye.msg.send.data.ImageMsgData;

/**
 * 图片群聊消息
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.05
 */
@Data
public class ImageQqMsg implements QqMsgParam<ImageMsgData> {
    /**
     * 类型
     */
    private final String type = GroupMsgTypeEnum.IMAGE.getCode();
    /**
     * 消息
     */
    private final ImageMsgData data;

    public ImageQqMsg(String base64) {
        this.data = new ImageMsgData("base64://" + base64);
    }
}