package pn.torn.goldeneye.torn.service.faction.oc;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.InitOrderConstants;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.faction.crime.TornFactionOcRefreshManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Torn Oc逻辑层
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.07.29
 */
@Service
@RequiredArgsConstructor
@Order(InitOrderConstants.TORN_OC)
public class TornFactionOcService {
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final DynamicTaskService taskService;
    private final TornOcCompleteNoticeService completeNoticeService;
    private final TornFactionOcRefreshManager ocRefreshManager;
    private final TornSettingFactionManager settingFactionManager;
    private final SysSettingDAO settingDao;
    private final ProjectProperty projectProperty;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        String lastRefreshTime = settingDao.querySettingValue(SettingConstants.KEY_OC_LOAD);
        LocalDateTime last = DateTimeUtils.convertToDateTime(lastRefreshTime);
        if (last.plusHours(1).isBefore(LocalDateTime.now())) {
            refreshOc(1);
        } else {
            scheduleOcTask(last);
        }
        completeNoticeService.init();
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
        List<TornSettingFactionDO> factionList = settingFactionManager.getList();
        List<CompletableFuture<Void>> futureList = new ArrayList<>();
        for (TornSettingFactionDO faction : factionList) {
            futureList.add(CompletableFuture.runAsync(() ->
                    ocRefreshManager.refreshOc(pageSize, faction.getId()), virtualThreadExecutor));
        }

        CompletableFuture.allOf(futureList.toArray(new CompletableFuture[0])).join();
        scheduleOcTask(LocalDateTime.now());
    }
}