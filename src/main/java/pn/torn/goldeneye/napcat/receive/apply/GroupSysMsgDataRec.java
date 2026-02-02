package pn.torn.goldeneye.napcat.receive.apply;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 群聊系统消息数据返回体
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.29
 */
@Data
public class GroupSysMsgDataRec {
    /**
     * 加群请求列表
     */
    @JsonProperty("join_requests")
    private List<GroupSysMsgJoinRec> joinRequests;
}