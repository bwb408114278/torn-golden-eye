package pn.torn.goldeneye.msg.receive.apply;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 群聊系统消息进群申请返回体
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.29
 */
@Data
public class GroupSysMsgJoinRec {
    /**
     * 请求ID
     */
    @JsonProperty("request_id")
    private long requestId;
    /**
     * 邀请者QQ
     */
    @JsonProperty("invitor_uin")
    private long invitorUin;
    /**
     * 邀请者昵称
     */
    @JsonProperty("invitor_nick")
    private String invitorNick;
    /**
     * 群号
     */
    @JsonProperty("group_id")
    private long groupId;
    /**
     * 附言
     */
    private String message;
    /**
     * 群名称
     */
    @JsonProperty("group_name")
    private String groupName;
    /**
     * 是否已处理
     */
    private boolean checked;
    /**
     * 操作者QQ
     */
    private int actor;
    /**
     * 申请人昵称
     */
    @JsonProperty("requester_nick")
    private String requesterNick;
}