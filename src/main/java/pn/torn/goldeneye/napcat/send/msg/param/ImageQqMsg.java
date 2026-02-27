package pn.torn.goldeneye.napcat.send.msg.param;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import pn.torn.goldeneye.constants.bot.enums.GroupMsgTypeEnum;
import pn.torn.goldeneye.napcat.send.msg.data.ImageMsgData;

/**
 * 图片群聊消息
 *
 * @author Bai
 * @version 0.5.0
 * @since 2025.08.05
 */
@Getter
@EqualsAndHashCode
@ToString
public class ImageQqMsg implements QqMsgParam<ImageMsgData> {
    /**
     * 类型
     */
    private final String type = GroupMsgTypeEnum.IMAGE.getCode();
    /**
     * 消息
     */
    private final ImageMsgData data;

    private ImageQqMsg(ImageMsgData data) {
        this.data = data;
    }

    public static ImageQqMsg fromBase64(String base64) {
        return new ImageQqMsg(new ImageMsgData("base64://" + base64, null));
    }

    public static ImageQqMsg fromUrl(String url) {
        return new ImageQqMsg(new ImageMsgData(null, url));
    }
}