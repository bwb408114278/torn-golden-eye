package pn.torn.goldeneye.torn.model.user.travel;

import lombok.Data;

/**
 * 用户旅行响应参数
 *
 * @author Bai
 * @version 1.0.0
 * @since 2026.03.04
 */
@Data
public class TornUserTravelVO {
    /**
     * 旅行数据
     */
    private TornUserTravelDataVO travel;
}