package pn.torn.goldeneye.msg.receive.apply;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 群聊系统消息返回体
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.29
 */
@Data
public class GroupSysMsgRec {
    /**
     * 状态 ok/failed
     */
    private String status;
    /**
     * 返回码
     */
    @JsonProperty("retcode")
    private int retCode;
    /**
     * 数据
     */
    private GroupSysMsgDataRec data;
    /**
     * 消息
     */
    private String message;
    /**
     * 提示
     */
    private String wording;
}