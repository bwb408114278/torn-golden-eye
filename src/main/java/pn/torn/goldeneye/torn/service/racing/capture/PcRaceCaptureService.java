package pn.torn.goldeneye.torn.service.racing.capture;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.InitOrderConstants;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.RacingConstants;
import pn.torn.goldeneye.repository.dao.racing.TornRacingRaceDAO;
import pn.torn.goldeneye.torn.model.user.racing.TornRaceDetailVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * SMTHPC赛事抓取编排逻辑层。
 *
 * <p>每日抓取由动态任务一次性任务承载，执行完毕在finally中自续期次日，失败不断链；
 * 启动时仅生产环境做一次补抓，停机跨多日的缺口按缺失处理，不回补。</p>
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Order(InitOrderConstants.TORN_PC_RACE)
public class PcRaceCaptureService {
    private final PcRaceDiscoveryService discoveryService;
    private final PcRacePersistService persistService;
    private final TornRacingRaceDAO raceDao;
    private final DynamicTaskService taskService;
    private final ProjectProperty projectProperty;

    /**
     * 应用启动补偿：仅生产环境执行，未到当日抓取时刻则排队，已过则按需补抓。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        LocalDateTime todayCaptureTime = LocalDate.now()
                .atTime(RacingConstants.CAPTURE_HOUR, RacingConstants.CAPTURE_MINUTE);
        if (LocalDateTime.now().isBefore(todayCaptureTime)) {
            addScheduleTask(todayCaptureTime);
            return;
        }

        if (raceDao.existsByBusinessDate(DateTimeUtils.getTornLocalDate().minusDays(1))) {
            addScheduleTask(nextCaptureTime());
            return;
        }

        captureDaily();
    }

    /**
     * 定时入口：抓取昨日赛事，finally中自续期次日抓取时刻，失败不断链。
     */
    public void captureDaily() {
        try {
            capture(DateTimeUtils.getTornLocalDate().minusDays(1));
        } finally {
            addScheduleTask(nextCaptureTime());
        }
    }

    /**
     * 抓取指定业务日期的赛事。
     *
     * <p>非事务编排，落库委托 {@link PcRacePersistService}；任何失败分支只记日志，
     * 不重试、不推送、不写部分数据。</p>
     *
     * @param businessDate 业务日期
     */
    public void capture(LocalDate businessDate) {
        if (raceDao.existsByBusinessDate(businessDate)) {
            log.info("SMTHPC赛事已抓取, businessDate={}", businessDate);
            return;
        }

        TornRaceDetailVO race = discoveryService.findRace(businessDate);
        if (race == null) {
            log.warn("未定位到SMTHPC赛事, businessDate={}", businessDate);
            return;
        }
        if (!RacingConstants.RACE_FINISHED_STATUS.equals(race.getStatus())) {
            log.warn("SMTHPC赛事未结束, businessDate={}, status={}", businessDate, race.getStatus());
            return;
        }
        if (CollectionUtils.isEmpty(race.getResults())) {
            log.warn("SMTHPC赛事成绩为空, businessDate={}", businessDate);
            return;
        }

        try {
            persistService.save(race, businessDate);
            log.info("SMTHPC赛事抓取完成, businessDate={}, raceId={}", businessDate, race.getId());
        } catch (DuplicateKeyException e) {
            log.info("SMTHPC赛事并发重复抓取, businessDate={}, raceId={}", businessDate, race.getId());
        }
    }

    /**
     * 添加次日抓取定时任务。
     *
     * @param execTime 任务执行时间
     */
    private void addScheduleTask(LocalDateTime execTime) {
        taskService.updateTask(RacingConstants.CAPTURE_TASK_ID, this::captureDaily, execTime);
    }

    /**
     * 计算次日抓取时刻。
     *
     * @return 次日抓取时刻
     */
    private LocalDateTime nextCaptureTime() {
        return LocalDate.now().plusDays(1)
                .atTime(RacingConstants.CAPTURE_HOUR, RacingConstants.CAPTURE_MINUTE);
    }
}
