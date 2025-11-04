package pn.torn.goldeneye.torn.service.faction.oc;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingFactionDAO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.faction.oc.TornFactionOcManager;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionOcDTO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionOcVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn Oc逻辑层
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.07.29
 */
@Service
@RequiredArgsConstructor
@Order(10002)
public class TornFactionOcService {
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final TornApi tornApi;
    private final DynamicTaskService taskService;
    private final TornFactionOcManager ocManager;
    private final SysSettingDAO settingDao;
    private final TornSettingFactionDAO settingFactionDao;
    private final ProjectProperty projectProperty;

    @PostConstruct
    public void init() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        String lastRefreshTime = settingDao.querySettingValue(SettingConstants.KEY_OC_LOAD);
        LocalDateTime last = DateTimeUtils.convertToDateTime(lastRefreshTime);
        if (last.plusHours(1).isBefore(LocalDateTime.now())) {
            virtualThreadExecutor.execute(() -> refreshOc(1));
        } else {
            scheduleOcTask(last);
        }
    }

    /**
     * 定时更新OC任务
     */
    public void scheduleOcTask(LocalDateTime last) {
        taskService.updateTask("oc-reload", () -> refreshOc(1), last.plusHours(1));
        settingDao.updateSetting(SettingConstants.KEY_OC_LOAD, DateTimeUtils.convertToString(last));
    }

    /**
     * 刷新OC
     */
    public void refreshOc(int pageSize) {
        List<TornSettingFactionDO> factionList = settingFactionDao.list();
        for (TornSettingFactionDO faction : factionList) {
            refreshOc(pageSize, faction.getId());
        }
        scheduleOcTask(LocalDateTime.now());
    }

    /**
     * 刷新OC
     */
    public void refreshOc(int pageSize, long factionId) {
        for (int pageNo = 1; pageNo <= pageSize; pageNo++) {
            try {
                Thread.sleep(1000L);
                TornFactionOcVO availableOc = tornApi.sendRequest(factionId,
                        new TornFactionOcDTO(pageNo, false), TornFactionOcVO.class);
                Thread.sleep(1000L);
                TornFactionOcVO completeOc = tornApi.sendRequest(factionId,
                        new TornFactionOcDTO(pageNo, true), TornFactionOcVO.class);

                if (availableOc != null && completeOc != null) {
                    ocManager.updateOc(factionId, availableOc.getCrimes(), completeOc.getCrimes());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}