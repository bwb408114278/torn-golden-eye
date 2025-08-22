package pn.torn.goldeneye.torn.model.user.oc;

import lombok.Data;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeVO;

/**
 * Torn用户OC响应参数
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.20
 */
@Data
public class TornUserOcVO {
    /**
     * 当前加入的OC
     */
    private TornFactionCrimeVO organizedCrime;
}