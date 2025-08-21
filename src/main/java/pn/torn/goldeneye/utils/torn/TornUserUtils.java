package pn.torn.goldeneye.utils.torn;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;

/**
 * Torn用户工具
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.20
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.NONE)
public class TornUserUtils {
    /**
     * 从消息发送人获取用户ID
     */
    public static long getUserIdFromSender(QqRecMsgSender sender) {
        String[] card = sender.getCard().split("\\[");
        if (card.length > 1) {
            return Long.parseLong(card[1].replace("]", ""));
        }

        return 0L;
    }
}