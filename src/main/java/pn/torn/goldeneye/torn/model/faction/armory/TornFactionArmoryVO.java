package pn.torn.goldeneye.torn.model.faction.armory;

import lombok.Data;

import java.util.List;

/**
 * Torn帮派物资响应参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.30
 */
@Data
public class TornFactionArmoryVO {
    /**
     * Booster
     */
    private List<TornFactionArmoryBoosterVO> boosters;
    /**
     * 医疗品
     */
    private List<TornFactionArmoryMedicalVO> medical;
    /**
     * 投掷武器
     */
    private List<TornFactionArmoryTempVO> temporary;
}