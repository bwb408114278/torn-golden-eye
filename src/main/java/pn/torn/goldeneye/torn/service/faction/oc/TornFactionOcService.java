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
import pn.torn.goldeneye.constants.torn.SettingConstants;
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

        String lastRefreshTime = settingDao.querySettingValue(SettingConstants.SETTING_KEY_OC_LOAD);
        LocalDateTime last = DateTimeUtils.convertToDateTime(lastRefreshTime);
        if (last.plusHours(1).isBefore(LocalDateTime.now())) {
            virtualThreadExecutor.execute(this::refreshOc);
        } else {
            scheduleOcTask(last);
        }

        scheduleRotationTask();
    }

    /**
     * 定时更新OC任务
     */
    public void scheduleOcTask(LocalDateTime last) {
        taskService.updateTask("oc-reload", this::refreshOc, last.plusHours(1));
        settingDao.updateSetting(SettingConstants.SETTING_KEY_OC_LOAD, DateTimeUtils.convertToString(last));
    }

    /**
     * 轮转队提醒任务
     */
    public void scheduleRotationTask() {
        for (int rank = 7; rank < 9; rank++) {
            long planId = Long.parseLong(settingDao.querySettingValue(SettingConstants.SETTING_KEY_OC_PLAN_ID + rank));
            TornFactionOcDO oc = ocDao.getById(planId);

            String taskId = "oc-valid-" + rank;
            TornFactionOcNoticeBO noticeParam = buildNoticeParam(planId, rank, taskId);

            taskService.updateTask("oc-ready-" + rank,
                    readyService.buildNotice(noticeParam), oc.getReadyTime().plusMinutes(-5));
            taskService.updateTask("oc-join-" + rank,
                    joinService.buildNotice(noticeParam), oc.getReadyTime());
            taskService.updateTask("oc-completed-" + rank,
                    () -> ocManager.completeOcData(List.of()), oc.getReadyTime().plusMinutes(2));
            taskService.updateTask(taskId, validService.buildNotice(noticeParam), oc.getReadyTime().plusMinutes(1L));
        }
    }

    /**
     * 刷新OC
     */
    public void refreshOc() {
        List<TornSettingFactionDO> factionList = settingFactionDao.list();
        for (TornSettingFactionDO faction : factionList) {
            TornApiKeyDO key = apiKeyConfig.getFactionKey(faction.getId(), true);
            if (key != null) {
                refreshOc(key.getFactionId());
            }
        }
        scheduleOcTask(LocalDateTime.now());
    }

    /**
     * 刷新OC
     *
     * @param factionId 帮派ID
     */
    public void refreshOc(long factionId) {
        TornFactionOcVO oc = tornApi.sendRequest(factionId, new TornFactionOcDTO(), TornFactionOcVO.class);
        if (oc != null) {
            ocManager.updateOc(factionId, oc.getCrimes());
        }
    }

    /**
     * 构建提醒参数
     */
    private TornFactionOcNoticeBO buildNoticeParam(long planId, int rank, String taskId) {
        int excludeRank = rank == 7 ? 8 : 7;
        String enableRanks = settingDao.querySettingValue(SettingConstants.SETTING_KEY_OC_ENABLE_RANK + rank);
        String[] enableRankStrArray = enableRanks.split(",");
        int[] enableRankArray = new int[enableRankStrArray.length];
        for (int i = 0; i < enableRankStrArray.length; i++) {
            enableRankArray[i] = Integer.parseInt(enableRankStrArray[i]);
        }

        return new TornFactionOcNoticeBO(planId, taskId, rank, excludeRank,
                () -> refreshOc(TornConstants.FACTION_PN_ID), this::scheduleRotationTask,
                0, 0, enableRankArray);
    }
}