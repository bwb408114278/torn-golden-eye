package pn.torn.goldeneye.torn.model.user.log;

import lombok.Data;

import java.util.List;

/**
 * Torn用户日志数据响应参数
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.30
 */
@Data
public class TornUserLogDataVO {
    /**
     * 发送人ID
     */
    private long sender;
    /**
     * 物品列表
     */
    private List<TornUserLogItemVO> items;
    /**
     * 附加消息
     */
    private String message;
}