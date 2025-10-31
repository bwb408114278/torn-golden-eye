package pn.torn.goldeneye.torn.model.faction.crime;

import lombok.Data;

import java.util.List;

/**
 * Torn OC Crime奖励响应参数
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.10.30
 */
@Data
public class TornFactionCrimeRewardVO {
    /**
     * 金钱
     */
    private Long money;
    /**
     * 物品
     */
    private List<TornFactionCrimeRewardItemVO> items;
}