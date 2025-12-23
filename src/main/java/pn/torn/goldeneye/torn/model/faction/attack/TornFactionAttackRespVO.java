package pn.torn.goldeneye.torn.model.faction.attack;

import lombok.Data;

import java.util.List;

/**
 * 帮派攻击记录响应参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.18
 */
@Data
public class TornFactionAttackRespVO {
    /**
     * 攻击记录列表
     */
    private List<TornFactionAttackVO> attacks;
}