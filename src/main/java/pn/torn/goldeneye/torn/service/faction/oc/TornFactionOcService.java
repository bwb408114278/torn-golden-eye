package pn.torn.goldeneye.torn.service.faction.oc;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.torn.manager.faction.oc.TornFactionOcManager;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionOcDTO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionOcVO;
import pn.torn.goldeneye.torn.service.faction.oc.notice.TornFactionOcCompleteService;
import pn.torn.goldeneye.torn.service.faction.oc.notice.TornFactionOcJoinService;
import pn.torn.goldeneye.torn.service.faction.oc.notice.TornFactionOcReadyService;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn Oc逻辑层
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.29
 */
@Service
@RequiredArgsConstructor
public class TornFactionOcService {
    private final TornApi tornApi;
    private final DynamicTaskService taskService;
    private final TornFactionOcReadyService readyService;
    private final TornFactionOcJoinService joinService;
    private final TornFactionOcCompleteService completeService;
    private final TornFactionOcManager ocManager;
    private final TornFactionOcDAO ocDao;
    private final SysSettingDAO settingDao;

    /**
     * 刷新OC
     */
    public void refreshOc() {
        TornFactionOcVO oc = tornApi.sendRequest(new TornFactionOcDTO(), TornFactionOcVO.class);
        if (oc == null) {
            taskService.updateTask(TornConstants.TASK_ID_OC_RELOAD, this::refreshOc,
                    DateTimeUtils.convertToInstant(LocalDateTime.now().plusMinutes(1)), null);
        } else {
            ocManager.updateOc(oc.getCrimes());
            LocalDateTime execTime = LocalDateTime.now();
            settingDao.updateSetting(TornConstants.SETTING_KEY_OC_LOAD, DateTimeUtils.convertToString(execTime));
            taskService.updateTask(TornConstants.TASK_ID_OC_RELOAD, this::refreshOc,
                    DateTimeUtils.convertToInstant(execTime.plusHours(1)), null);

            updateScheduleTask();
        }
    }

    /**
     * 更新定时提醒
     */
    public void updateScheduleTask() {
        List<TornFactionOcDO> ocList = ocDao.queryRotationExecList();
        for (TornFactionOcDO oc : ocList) {
            taskService.updateTask("oc-ready-" + oc.getRank(),
                    readyService.buildNotice(oc.getId()),
                    DateTimeUtils.convertToInstant(oc.getReadyTime().plusMinutes(-5)), null);
            taskService.updateTask("oc-join-" + oc.getRank(),
                    joinService.buildNotice(oc.getId()),
                    DateTimeUtils.convertToInstant(oc.getReadyTime()), null);
            taskService.updateTask("oc-completed-" + oc.getRank(),
                    completeService.buildNotice(oc.getId(), this::refreshOc),
                    DateTimeUtils.convertToInstant(oc.getReadyTime().plusMinutes(2)), null);
        }
    }
}