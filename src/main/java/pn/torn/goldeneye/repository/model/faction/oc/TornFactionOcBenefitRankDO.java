package pn.torn.goldeneye.repository.model.faction.oc;

import lombok.Data;

/**
 * OC收益榜查询结果
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.10
 */
@Data
public class TornFactionOcBenefitRankDO {
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 用户昵称
     */
    private String nickname;
    /**
     * 总数
     */
    private Long benefit;
}