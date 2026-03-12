package pn.torn.goldeneye.napcat.receive.member;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 群聊成员数据
 *
 * @author Bai
 * @version 1.0.0
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
    /**
     * 群角色
     */
    private String role;
}