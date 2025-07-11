package pn.torn.goldeneye.msg.send;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;

import java.util.List;

/**
 * 群聊消息请求参数
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.06.22
 */
@Data
@AllArgsConstructor
class GroupMsgReqParam {
    /**
     * 群号
     */
    @JsonProperty("group_id")
    private long groupId;
    /**
     * 消息列表
     */
    private List<GroupMsgParam<?>> message;
}