package pn.torn.goldeneye.repository.model.faction.oc;

import lombok.Data;

import java.math.BigDecimal;

/**
 * OC成功率排行
 *
 * @author Bai
 * @version 1.2.6
 * @since 2026.06.26
 */
@Data
public class OcSuccessRankDO {
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 帮派ID
     */
    private Long factionId;
    /**
     * 参与OC总数
     */
    private Integer totalOcCount;
    /**
     * 成功OC数
     */
    private Integer successCount;
    /**
     * 成功率
     */
    private BigDecimal successRate;
}
