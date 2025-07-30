package pn.torn.goldeneye.torn.model.faction.crime;

import lombok.Data;

import java.util.List;

/**
 * Torn OC响应参数
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.29
 */
@Data
public class TornFactionOcVO {
    /**
     * Crime列表
     */
    private List<TornFactionCrimeVO> crimes;
}