package pn.torn.goldeneye.torn.model.faction.crime.recommend;

import lombok.AllArgsConstructor;
import lombok.Data;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;

/**
 * OC和岗位键值对业务模型
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.14
 */
@Data
@AllArgsConstructor
public class OcSlotDictBO {
    /**
     * OC
     */
    private TornFactionOcDO oc;
    /**
     * 岗位
     */
    private TornFactionOcSlotDO slot;
}