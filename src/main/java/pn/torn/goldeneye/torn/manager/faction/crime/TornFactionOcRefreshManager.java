package pn.torn.goldeneye.torn.manager.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionOcDTO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionOcVO;

import java.time.LocalDateTime;

/**
 * OC刷新公共逻辑层
 *
 * @author Bai
 * @version 1.0.0
 * @since 2025.11.26
 */
@Component
@RequiredArgsConstructor
public class TornFactionOcRefreshManager {
    private final TornApi tornApi;
    private final TornFactionOcManager ocManager;

    /**
     * 刷新OC
     */
    public void refreshOc(int pageSize, long factionId) {
        for (int pageNo = 1; pageNo <= pageSize; pageNo++) {
            try {
                LocalDateTime execTime = LocalDateTime.now();
                Thread.sleep(1000L);
                TornFactionOcVO availableOc = tornApi.sendRequest(factionId,
                        new TornFactionOcDTO(pageNo, false), TornFactionOcVO.class);
                Thread.sleep(1000L);
                TornFactionOcVO completeOc = tornApi.sendRequest(factionId,
                        new TornFactionOcDTO(pageNo, true), TornFactionOcVO.class);

                if (availableOc != null && completeOc != null) {
                    ocManager.updateOc(factionId, execTime, availableOc.getCrimes(), completeOc.getCrimes());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}