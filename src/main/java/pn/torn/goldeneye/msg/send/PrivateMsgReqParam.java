package pn.torn.goldeneye.msg.send;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;

import java.util.List;

/**
 * 私聊消息请求参数
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.21
 */
@Data
@AllArgsConstructor
class PrivateMsgReqParam {
    /**
     * QQ号
     */
    @JsonProperty("user_id")
    private long userId;
    /**
     * 消息列表
     */
    private List<QqMsgParam<?>> message;
}