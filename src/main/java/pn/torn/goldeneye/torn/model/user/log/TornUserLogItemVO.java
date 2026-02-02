package pn.torn.goldeneye.torn.model.user.log;

import lombok.Data;

/**
 * Torn用户日志物品响应参数
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.30
 */
@Data
public class TornUserLogItemVO {
    /**
     * 物品ID
     */
    private int id;
    /**
     * 物品UID
     */
    private String uid;
    /**
     * 物品数量
     */
    private int qty;
}