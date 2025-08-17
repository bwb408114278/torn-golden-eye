package pn.torn.goldeneye.torn.service.faction.oc;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.torn.manager.faction.oc.TornFactionOcManager;
import pn.torn.goldeneye.torn.service.faction.oc.notice.TornFactionOcJoinService;
import pn.torn.goldeneye.torn.service.faction.oc.notice.TornFactionOcReadyService;
import pn.torn.goldeneye.torn.service.faction.oc.notice.TornFactionOcValidNoticeBO;
import pn.torn.goldeneye.torn.service.faction.oc.notice.TornFactionOcValidService;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.util.List;

/**
 * Torn OC临时轮转队逻辑层
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.15
 */
@Service
@RequiredArgsConstructor
@Order(10003)
public class TornFactionOcTempService {
    private final DynamicTaskService taskService;
    private final TornFactionOcService ocService;
    private final TornFactionOcReadyService readyService;
    private final TornFactionOcJoinService joinService;
    private final TornFactionOcValidService validService;
    private final TornFactionOcManager ocManager;
    private final TornFactionOcDAO ocDao;
    private final SysSettingDAO settingDao;
    private static final String TEMP_FLAG = "TEMP";

    @PostConstruct
    public void init() {
        if (!ocManager.isCheckEnableTemp()) {
            return;
        }

        updateScheduleTask();
    }

    /**
     * 更新定时提醒
     */
    public void updateScheduleTask() {
        String planOcId = settingDao.querySettingValue(TornConstants.SETTING_KEY_OC_PLAN_ID + TEMP_FLAG);
        if (planOcId == null) {
            return;
        }

        TornFactionOcDO oc = ocDao.getById(Long.parseLong(planOcId));
        taskService.updateTask("oc-ready-" + TEMP_FLAG,
                readyService.buildNotice(oc.getId()),
                DateTimeUtils.convertToInstant(oc.getReadyTime().plusMinutes(-5)), null);
        taskService.updateTask("oc-join-" + TEMP_FLAG,
                joinService.buildNotice(oc.getId()),
                DateTimeUtils.convertToInstant(oc.getReadyTime()), null);
        taskService.updateTask("oc-completed-" + TEMP_FLAG,
                () -> ocManager.completeOcData(List.of()),
                DateTimeUtils.convertToInstant(oc.getReadyTime().plusMinutes(2)), null);

        String taskId = TornConstants.TASK_ID_OC_VALID + TEMP_FLAG;
        TornFactionOcValidNoticeBO validParam = new TornFactionOcValidNoticeBO(oc.getId(), taskId,
                TornConstants.SETTING_KEY_OC_PLAN_ID + TEMP_FLAG,
                TornConstants.SETTING_KEY_OC_PLAN_ID + "7",
                TornConstants.SETTING_KEY_OC_REC_ID + TEMP_FLAG,
                TornConstants.SETTING_KEY_OC_REC_ID + "7",
                ocService::refreshOc, this::updateScheduleTask, 0, 0, 7, 8);
        taskService.updateTask(taskId,
                validService.buildNotice(validParam),
                DateTimeUtils.convertToInstant(oc.getReadyTime().plusMinutes(1L)), null);
    }
}