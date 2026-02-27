package pn.torn.goldeneye.napcat.receive.apply;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.napcat.receive.BaseQqRec;

/**
 * 群聊系统消息返回体
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.29
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class GroupSysMsgRec extends BaseQqRec {
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