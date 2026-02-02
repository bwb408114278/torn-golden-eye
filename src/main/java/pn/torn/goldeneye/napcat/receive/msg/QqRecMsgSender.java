package pn.torn.goldeneye.napcat.receive.msg;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.springframework.util.StringUtils;

/**
 * 群聊接收消息 - 发送人信息
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.11
 */
@Data
public class QqRecMsgSender {
    /**
     * 发送人QQ号
     */
    @JsonProperty("user_id")
    private long userId;
    /**
     * 发送人昵称
     */
    private String nickname;
    /**
     * 发送人群名片
     */
    private String card;
    /**
     * 群聊角色
     */
    private String role;

    public String getCard() {
        return StringUtils.hasText(this.card) ? this.card : this.nickname;
    }
}