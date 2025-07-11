package pn.torn.goldeneye.msg.receive;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 群聊接收消息
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.11
 */
@Data
public class GroupRecMsg {
    /**
     * 自己的QQ
     */
    @JsonProperty("self_id")
    private long selfId;
    /**
     * 发送消息的QQ
     */
    @JsonProperty("user_id")
    private long userId;
    /**
     * 消息时间戳
     */
    private long time;
    /**
     * 消息ID
     */
    @JsonProperty("message_id")
    private long messageId;
    /**
     * 消息Seq
     */
    @JsonProperty("message_seq")
    private long messageSeq;
    /**
     * 消息真实ID
     */
    @JsonProperty("real_id")
    private long realId;
    /**
     * 消息真实Seq
     */
    @JsonProperty("real_seq")
    private String realSeq;
    /**
     * 消息类型
     */
    @JsonProperty("message_type")
    private String messageType;
    /**
     * 消息子类型
     */
    @JsonProperty("sub_type")
    private String subType;
    /**
     * 原始消息
     */
    @JsonProperty("raw_message")
    private String rawMessage;
    /**
     * 消息格式
     */
    @JsonProperty("message_format")
    private String messageFormat;
    /**
     * 发送类型
     */
    @JsonProperty("post_type")
    private String postType;
    /**
     * 群聊ID
     */
    @JsonProperty("group_id")
    private long groupId;
    /**
     * 字体
     */
    private int font;
    /**
     * 发送人详细信息
     */
    private GroupRecSender sender;
    /**
     * 消息详细列表
     */
    private List<GroupRecMsgDetail> message;
}