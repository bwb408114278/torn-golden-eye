package pn.torn.goldeneye.constants.bot.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 群聊消息类型
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.06.22
 */
@Getter
@AllArgsConstructor
public enum GroupMsgTypeEnum {
    /**
     * 文本消息
     */
    TEXT("text"),
    /**
     * AT某人
     */
    AT("at"),
    /**
     * 图片
     */
    IMAGE("image");

    private final String code;
}