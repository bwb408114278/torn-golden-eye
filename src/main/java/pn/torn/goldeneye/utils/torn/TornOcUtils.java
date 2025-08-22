package pn.torn.goldeneye.utils.torn;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcNoticeDO;
import pn.torn.goldeneye.torn.model.faction.crime.constraint.TornFactionOc;
import pn.torn.goldeneye.torn.model.faction.crime.constraint.TornFactionOcSlot;

import java.util.ArrayList;
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
    public static final String OC_9_PREV = "Stacking the Deck";
    public static final String OC_8_NORMAL = "Clinical Precision";
    /**
     * 不参加轮转队的OC名称
     */
    private static final List<String> NOT_ROTATION_OC_NAME = new ArrayList<>();

    static {
        NOT_ROTATION_OC_NAME.add(OC_9_PREV);
        NOT_ROTATION_OC_NAME.add(OC_8_NORMAL);
    }

    /**
     * 是否轮转队OC
     *
     * @return true为是
     */
    public static boolean isRotationOc(TornFactionOc oc, List<? extends TornFactionOcSlot> slotList,
                                       List<TornFactionOcNoticeDO> skipList) {
        boolean notRotationRank = !oc.getRank().equals(8) && !oc.getRank().equals(7);
        if (notRotationRank || NOT_ROTATION_OC_NAME.contains(oc.getName())) {
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
        return oc.getRank().equals(8) && oc.getName().equals(OC_9_PREV);
    }
}