package pn.torn.goldeneye.repository.model.faction.oc;

import lombok.Data;

/**
 * OC空转榜查询结果
 *
 * @author Bai
 * @version 1.2.2
 * @since 2026.06.12
 */
@Data
public class TornFactionOcIdleRankDO {
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 帮派ID
     */
    private Long factionId;
    /**
     * 空转秒数
     */
    private Long idleSeconds;
    /**
     * OC数量
     */
    private Long ocCount;
}
