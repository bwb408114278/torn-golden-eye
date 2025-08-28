package pn.torn.goldeneye.torn.service.faction.oc;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingFactionDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.faction.oc.TornFactionOcManager;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionOcDTO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionOcVO;
import pn.torn.goldeneye.torn.service.faction.oc.notice.TornFactionOcJoinService;
import pn.torn.goldeneye.torn.service.faction.oc.notice.TornFactionOcNoticeBO;
import pn.torn.goldeneye.torn.service.faction.oc.notice.TornFactionOcReadyService;
import pn.torn.goldeneye.torn.service.faction.oc.notice.TornFactionOcValidService;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn Oc逻辑层
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.07.29
 */
@Service
@RequiredArgsConstructor
@Order(10001)
public class TornFactionOcService {
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final TornApi tornApi;
    private final TornApiKeyConfig apiKeyConfig;
    private final DynamicTaskService taskService;
    private final TornFactionOcReadyService readyService;
    private final TornFactionOcJoinService joinService;
    private final TornFactionOcValidService validService;
    private final TornFactionOcManager ocManager;
    private final TornFactionOcDAO ocDao;
    private final SysSettingDAO settingDao;
    private final TornSettingFactionDAO settingFactionDao;
    private final ProjectProperty projectProperty;

    @PostConstruct
    public void init() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        String lastRefreshTime = settingDao.querySettingValue(TornConstants.SETTING_KEY_OC_LOAD);
        LocalDateTime last = DateTimeUtils.convertToDateTime(lastRefreshTime);
        if (last.plusHours(1).isBefore(LocalDateTime.now())) {
            virtualThreadExecutor.execute(this::scheduleOcTask);
        } else {
            updateScheduleTask();
        }
    }

    /**
     * 计划OC任务
     */
    public void scheduleOcTask() {
        List<TornSettingFactionDO> factionList = settingFactionDao.list();
        for (TornSettingFactionDO faction : factionList) {
            TornApiKeyDO key = apiKeyConfig.getFactionKey(faction.getId(), true);
            if (key != null) {
                refreshOc(key.getFactionId());
            }
        }

        updateScheduleTask();
        settingDao.updateSetting(TornConstants.SETTING_KEY_OC_LOAD, DateTimeUtils.convertToString(LocalDateTime.now()));
    }

    /**
     * 刷新OC
     */
    public void refreshOc(long factionId) {
        TornFactionOcVO oc = tornApi.sendRequest(factionId, new TornFactionOcDTO(), TornFactionOcVO.class);
        if (oc != null) {
            ocManager.updateOc(factionId, oc.getCrimes());
        }
    }

    /**
     * 更新定时提醒
     */
    public void updateScheduleTask() {
        String lastRefreshTime = settingDao.querySettingValue(TornConstants.SETTING_KEY_OC_LOAD);
        LocalDateTime last = DateTimeUtils.convertToDateTime(lastRefreshTime);
        taskService.updateTask(TornConstants.TASK_ID_OC_RELOAD, this::scheduleOcTask, last.plusHours(1));

        List<TornFactionOcDO> ocList = ocDao.queryRotationExecList(ocManager.isCheckEnableTemp());
        for (TornFactionOcDO oc : ocList) {
            String taskId = TornConstants.TASK_ID_OC_VALID + oc.getRank();
            TornFactionOcNoticeBO noticeParam = new TornFactionOcNoticeBO(oc.getId(), taskId,
                    TornConstants.SETTING_KEY_OC_PLAN_ID + oc.getRank(),
                    TornConstants.SETTING_KEY_OC_PLAN_ID + "TEMP",
                    TornConstants.SETTING_KEY_OC_REC_ID + oc.getRank(),
                    TornConstants.SETTING_KEY_OC_REC_ID + "TEMP",
                    () -> refreshOc(TornConstants.FACTION_PN_ID), this::updateScheduleTask,
                    0, 0, oc.getRank());

            taskService.updateTask(TornConstants.TASK_ID_OC_READY + oc.getRank(),
                    readyService.buildNotice(noticeParam), oc.getReadyTime().plusMinutes(-5));
            taskService.updateTask(TornConstants.TASK_ID_OC_JOIN + oc.getRank(),
                    joinService.buildNotice(noticeParam), oc.getReadyTime());
            taskService.updateTask(TornConstants.TASK_ID_OC_COMPLETE + oc.getRank(),
                    () -> ocManager.completeOcData(List.of()), oc.getReadyTime().plusMinutes(2));
            taskService.updateTask(taskId, validService.buildNotice(noticeParam), oc.getReadyTime().plusMinutes(1L));
        }
    }
}