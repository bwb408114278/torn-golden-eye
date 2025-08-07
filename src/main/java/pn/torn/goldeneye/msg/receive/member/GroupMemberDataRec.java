package pn.torn.goldeneye.msg.receive.member;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 群聊成员数据
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.31
 */
@Data
public class GroupMemberDataRec {
    /**
     * QQ号
     */
    @JsonProperty("user_id")
    private long userId;
    /**
     * 群名片
     */
    private String card;
}