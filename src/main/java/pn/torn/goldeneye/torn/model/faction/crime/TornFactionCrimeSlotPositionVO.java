package pn.torn.goldeneye.torn.model.faction.crime;

import lombok.Data;

/**
 * Torn OC Slot岗位响应参数
 *
 * @author Bai
 * @version 1.0.0
 * @since 2026.03.12
 */
@Data
public class TornFactionCrimeSlotPositionVO {
    /**
     * 岗位ID
     */
    private String id;
    /**
     * 岗位标签
     */
    private String label;
    /**
     * 岗位编号
     */
    private Integer number;
}