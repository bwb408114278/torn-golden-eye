package pn.torn.goldeneye.torn.service.faction.oc;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.property.TestProperty;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.msg.send.GroupMsgHttpBuilder;
import pn.torn.goldeneye.msg.send.param.TextGroupMsg;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.torn.manager.faction.oc.TornFactionOcManager;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionOcDTO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionOcVO;
import pn.torn.goldeneye.torn.service.faction.oc.notice.TornFactionOcJoinService;
import pn.torn.goldeneye.torn.service.faction.oc.notice.TornFactionOcReadyService;
import pn.torn.goldeneye.torn.service.faction.oc.notice.TornFactionOcValidNoticeBO;
import pn.torn.goldeneye.torn.service.faction.oc.notice.TornFactionOcValidService;
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
@Order(10001)
public class TornFactionOcService {
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final Bot bot;
    private final TornApi tornApi;
    private final DynamicTaskService taskService;
    private final TornFactionOcReadyService readyService;
    private final TornFactionOcJoinService joinService;
    private final TornFactionOcValidService validService;
    private final TornFactionOcManager ocManager;
    private final TornFactionOcDAO ocDao;
    private final SysSettingDAO settingDao;
    private final TestProperty testProperty;

    @PostConstruct
    public void init() {
        String lastRefreshTime = settingDao.querySettingValue(TornConstants.SETTING_KEY_OC_LOAD);
        LocalDateTime last = DateTimeUtils.convertToDateTime(lastRefreshTime);
        if (last.plusHours(1).isBefore(LocalDateTime.now())) {
            virtualThreadExecutor.execute(this::scheduleOcTask);
        } else {
            updateScheduleTask();
        }

        List<TornFactionOcDO> ocList = ocDao.queryRotationExecList(ocManager.isCheckEnableTemp());
        GroupMsgHttpBuilder builder = new GroupMsgHttpBuilder()
                .setGroupId(testProperty.getGroupId())
                .addMsg(new TextGroupMsg("OC轮转队加载完成"));
        if (CollectionUtils.isEmpty(ocList)) {
            builder.addMsg(new TextGroupMsg("\n当前没有轮转队"));
        } else {
            for (TornFactionOcDO oc : ocList) {
                builder.addMsg(new TextGroupMsg("\n" + oc.getRank() + "级: 抢车位时间为" +
                        DateTimeUtils.convertToString(oc.getReadyTime())));
            }
        }

        bot.sendRequest(builder.build(), String.class);
    }

    /**
     * 计划OC任务
     */
    public void scheduleOcTask() {
        refreshOc();
        updateScheduleTask();
    }

    /**
     * 刷新OC
     */
    public void refreshOc() {
        TornFactionOcVO oc = tornApi.sendRequest(new TornFactionOcDTO(), TornFactionOcVO.class);
        if (oc == null) {
            refreshOc();
        } else {
            ocManager.updateOc(oc.getCrimes());
            settingDao.updateSetting(TornConstants.SETTING_KEY_OC_LOAD,
                    DateTimeUtils.convertToString(LocalDateTime.now()));
        }
    }

    /**
     * 更新定时提醒
     */
    public void updateScheduleTask() {
        String lastRefreshTime = settingDao.querySettingValue(TornConstants.SETTING_KEY_OC_LOAD);
        LocalDateTime last = DateTimeUtils.convertToDateTime(lastRefreshTime);
        taskService.updateTask(TornConstants.TASK_ID_OC_RELOAD, this::scheduleOcTask,
                DateTimeUtils.convertToInstant(last.plusHours(1)), null);

        List<TornFactionOcDO> ocList = ocDao.queryRotationExecList(ocManager.isCheckEnableTemp());
        for (TornFactionOcDO oc : ocList) {
            taskService.updateTask("oc-ready-" + oc.getRank(),
                    readyService.buildNotice(oc.getId()),
                    DateTimeUtils.convertToInstant(oc.getReadyTime().plusMinutes(-5)), null);
            taskService.updateTask("oc-join-" + oc.getRank(),
                    joinService.buildNotice(oc.getId()),
                    DateTimeUtils.convertToInstant(oc.getReadyTime()), null);
            taskService.updateTask("oc-completed-" + oc.getRank(),
                    () -> ocManager.completeOcData(List.of()),
                    DateTimeUtils.convertToInstant(oc.getReadyTime().plusMinutes(2)), null);

            String taskId = TornConstants.TASK_ID_OC_VALID + oc.getRank();
            TornFactionOcValidNoticeBO validParam = new TornFactionOcValidNoticeBO(oc.getId(), taskId,
                    TornConstants.SETTING_KEY_OC_PLAN_ID + oc.getRank(),
                    TornConstants.SETTING_KEY_OC_PLAN_ID + "TEMP",
                    TornConstants.SETTING_KEY_OC_REC_ID + oc.getRank(),
                    TornConstants.SETTING_KEY_OC_REC_ID + "TEMP",
                    this::refreshOc, this::updateScheduleTask, 0, 0, oc.getRank());
            taskService.updateTask(taskId,
                    validService.buildNotice(validParam),
//                    DateTimeUtils.convertToInstant(oc.getReadyTime().plusMinutes(-2)), null);
                    DateTimeUtils.convertToInstant(oc.getReadyTime().plusMinutes(1L)), null);
        }
    }
}