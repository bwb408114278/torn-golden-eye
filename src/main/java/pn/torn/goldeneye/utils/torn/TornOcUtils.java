package pn.torn.goldeneye.utils.torn;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcNoticeDO;
import pn.torn.goldeneye.torn.model.faction.crime.constraint.TornFactionOc;
import pn.torn.goldeneye.torn.model.faction.crime.constraint.TornFactionOcSlot;

import java.util.List;

/**
 * Torn Oc工具
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.18
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.NONE)
public class TornOcUtils {
    /**
     * 8级 oc chain名称
     */
    private static final String OC_RANK_8_CHAIN = "Stacking the Deck";

    /**
     * 是否轮转队OC
     *
     * @return true为是
     */
    public static boolean isRotationOc(TornFactionOc oc, List<? extends TornFactionOcSlot> slotList,
                                       List<TornFactionOcNoticeDO> skipList) {
        boolean notRotationRank = !oc.getRank().equals(8) && !oc.getRank().equals(7);
        if (notRotationRank || isChainOc(oc)) {
            return false;
        }

        for (TornFactionOcSlot slot : slotList) {
            boolean isSkip = skipList.stream().anyMatch(p ->
                    p.getUserId().equals(slot.getUserId()) && p.getRank().equals(oc.getRank()));
            if (isSkip) {
                return false;
            }
        }

        return true;
    }

    /**
     * 是否chain oc
     *
     * @return true为是
     */
    public static boolean isChainOc(TornFactionOc oc) {
        return oc.getRank().equals(8) && oc.getName().equals(OC_RANK_8_CHAIN);
    }
}