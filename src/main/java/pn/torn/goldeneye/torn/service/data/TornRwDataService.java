package pn.torn.goldeneye.torn.service.data;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.InitOrderConstants;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwDAO;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.model.faction.rw.TornFactionRwDTO;
import pn.torn.goldeneye.torn.model.faction.rw.TornFactionRwRespVO;
import pn.torn.goldeneye.torn.model.faction.rw.TornFactionRwVO;
import pn.torn.goldeneye.torn.service.faction.attack.TornFactionAttackService;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * TornRw数据逻辑层
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.25
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Order(InitOrderConstants.TORN_RW)
public class TornRwDataService {
    private final DynamicTaskService taskService;
    private final TornApi tornApi;
    private final TornFactionAttackService attackService;
    private final TornSettingFactionManager settingFactionManager;
    private final SysSettingDAO settingDao;
    private final TornFactionRwDAO rwDao;
    private final ProjectProperty projectProperty;

    private static final LocalTime LOW_FREQUENCY_START = LocalTime.of(0, 0);
    private static final LocalTime LOW_FREQUENCY_END = LocalTime.of(8, 0);
    private static final long NORMAL_INTERVAL_MINUTES = 3;
    private static final long LOW_FREQUENCY_INTERVAL_MINUTES = 60;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        List<TornFactionRwDO> currentRwList = rwDao.lambdaQuery().isNull(TornFactionRwDO::getEndTime).list();
        if (CollectionUtils.isEmpty(currentRwList)) {
            return;
        }

        for (TornFactionRwDO rw : currentRwList) {
            TornSettingFactionDO faction = settingFactionManager.getIdMap().get(rw.getFactionId());
            String value = settingDao.querySettingValue(faction.getFactionShortName() + "_" + SettingConstants.KEY_RW_LOAD);
            LocalDateTime from = DateTimeUtils.convertToDateTime(value);
            long intervalMinutes = getIntervalMinutes(LocalDateTime.now());

            if (LocalDateTime.now().minusMinutes(intervalMinutes).isAfter(from)) {
                spiderRwData(faction, from);
            } else {
                addScheduleTask(faction, from);
            }
        }
    }

    /**
     * 爬取RW数据
     */
    public void spiderRwData(TornSettingFactionDO faction, LocalDateTime from) {
        TornFactionRwRespVO resp = tornApi.sendRequest(faction.getId(), new TornFactionRwDTO(), TornFactionRwRespVO.class);
        if (resp == null || CollectionUtils.isEmpty(resp.getRwList())) {
            throw new BizException("未查询到RW数据");
        }

        TornFactionRwVO currentRw = resp.getRwList().getFirst();
        spiderRwData(currentRw, faction, from);
    }

    /**
     * 爬取RW数据
     */
    public void spiderRwData(TornFactionRwVO currentRw, TornSettingFactionDO faction, LocalDateTime from) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = DateTimeUtils.convertToDateTime(currentRw.getStart());
        LocalDateTime to;
        if (currentRw.getEnd() != 0L) {
            to = DateTimeUtils.convertToDateTime(currentRw.getEnd());
            rwDao.lambdaUpdate()
                    .set(TornFactionRwDO::getEndTime, to)
                    .eq(TornFactionRwDO::getId, currentRw.getId())
                    .update();
        } else {
            to = start.isAfter(now) ? start : now;
            addScheduleTask(faction, to);
        }

        if (now.isAfter(start)) {
            attackService.spiderAttackData(faction, currentRw.getOpponentFaction(faction.getId()).getId(), from, to);
        }
    }

    /**
     * 添加定时任务
     */
    public void addScheduleTask(TornSettingFactionDO faction, LocalDateTime from) {
        LocalDateTime nextExecutionTime = calculateNextExecutionTime(from);

        settingDao.updateSetting(faction.getFactionShortName() + "_" + SettingConstants.KEY_RW_LOAD,
                DateTimeUtils.convertToString(from));
        taskService.updateTask(faction.getFactionShortName() + "-" + "rw-data-reload",
                () -> this.spiderRwData(faction, from),
                nextExecutionTime);
    }

    /**
     * 判断是否在低频时段（0点到8点）
     */
    private boolean isLowFrequencyPeriod(LocalDateTime dateTime) {
        LocalTime time = dateTime.toLocalTime();
        return !time.isBefore(LOW_FREQUENCY_START) && time.isBefore(LOW_FREQUENCY_END);
    }

    /**
     * 根据当前时间获取抓取间隔
     */
    private long getIntervalMinutes(LocalDateTime dateTime) {
        return isLowFrequencyPeriod(dateTime) ? LOW_FREQUENCY_INTERVAL_MINUTES : NORMAL_INTERVAL_MINUTES;
    }

    /**
     * 计算下次执行时间
     */
    private LocalDateTime calculateNextExecutionTime(LocalDateTime currentTime) {
        long intervalMinutes = getIntervalMinutes(currentTime);
        LocalDateTime nextTime = currentTime.plusMinutes(intervalMinutes);

        // 如果当前是正常频率时段，但下次执行会进入低频时段
        if (!isLowFrequencyPeriod(currentTime) && isLowFrequencyPeriod(nextTime)) {
            // 检查是否需要调整到低频时段的整点
            LocalDateTime nextHour = nextTime.withMinute(0).withSecond(0).withNano(0);
            if (nextHour.isAfter(currentTime)) {
                return nextHour;
            }
        }

        return nextTime;
    }
}