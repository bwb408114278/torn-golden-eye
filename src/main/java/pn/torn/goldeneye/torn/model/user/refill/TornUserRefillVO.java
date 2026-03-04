package pn.torn.goldeneye.torn.model.user.refill;

import lombok.Data;

/**
 * 用户Refill响应参数
 *
 * @author Bai
 * @version 1.0.0
 * @since 2026.03.04
 */
@Data
public class TornUserRefillVO {
    /**
     * Refill数据
     */
    private TornUserRefillDataVO refills;
}