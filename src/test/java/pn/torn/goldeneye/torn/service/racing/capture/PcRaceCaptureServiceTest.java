package pn.torn.goldeneye.torn.service.racing.capture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.torn.RacingConstants;
import pn.torn.goldeneye.repository.dao.racing.TornRacingRaceDAO;
import pn.torn.goldeneye.torn.model.user.racing.TornRaceDetailVO;
import pn.torn.goldeneye.torn.model.user.racing.TornRaceResultVO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * PC赛车抓取编排测试。
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PC赛车抓取编排测试")
class PcRaceCaptureServiceTest {
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 1, 4);
    private static final LocalDateTime NEXT_CAPTURE_TIME = LocalDate.now().plusDays(1)
            .atTime(RacingConstants.CAPTURE_HOUR, RacingConstants.CAPTURE_MINUTE);

    @Mock
    private PcRaceDiscoveryService discoveryService;
    @Mock
    private PcRacePersistService persistService;
    @Mock
    private TornRacingRaceDAO raceDao;
    @Mock
    private DynamicTaskService taskService;
    @Mock
    private ProjectProperty projectProperty;

    private PcRaceCaptureService captureService;

    @BeforeEach
    void setUp() {
        captureService = new PcRaceCaptureService(discoveryService, persistService, raceDao, taskService,
                projectProperty);
    }

    @Test
    @DisplayName("已有主行时跳过定位与落库")
    void capture_shouldSkipWhenRaceAlreadyCaptured() {
        when(raceDao.existsByBusinessDate(BUSINESS_DATE)).thenReturn(true);

        captureService.capture(BUSINESS_DATE);

        verifyNoInteractions(discoveryService, persistService);
    }

    @Test
    @DisplayName("赛事未结束时只记日志不落库")
    void capture_shouldNotPersistWhenRaceNotFinished() {
        when(discoveryService.findRace(BUSINESS_DATE))
                .thenReturn(race("racing", List.of(result(1L))));

        captureService.capture(BUSINESS_DATE);

        verify(persistService, never()).save(any(), any());
    }

    @Test
    @DisplayName("成绩为空时不落库")
    void capture_shouldNotPersistWhenResultsEmpty() {
        when(discoveryService.findRace(BUSINESS_DATE))
                .thenReturn(race(RacingConstants.RACE_FINISHED_STATUS, List.of()));

        captureService.capture(BUSINESS_DATE);

        verify(persistService, never()).save(any(), any());
    }

    @Test
    @DisplayName("赛事已结束且成绩非空时调用一次落库")
    void capture_shouldPersistOnceOnNormalPath() {
        TornRaceDetailVO race = race(RacingConstants.RACE_FINISHED_STATUS, List.of(result(1L)));
        when(discoveryService.findRace(BUSINESS_DATE)).thenReturn(race);

        captureService.capture(BUSINESS_DATE);

        verify(persistService, times(1)).save(race, BUSINESS_DATE);
    }

    @Test
    @DisplayName("非生产环境启动时不抓取也不排定任务")
    void init_shouldSkipOutsideProd() {
        when(projectProperty.getEnv()).thenReturn("dev");

        captureService.init();

        verifyNoInteractions(discoveryService, raceDao, taskService);
    }

    @Test
    @DisplayName("抓取失败时仍在finally中自续期次日")
    void captureDaily_shouldRenewScheduleEvenOnFailure() {
        when(discoveryService.findRace(any(LocalDate.class)))
                .thenThrow(new IllegalStateException("定位失败"));

        assertThrows(IllegalStateException.class, () -> captureService.captureDaily());

        verify(taskService).updateTask(eq(RacingConstants.CAPTURE_TASK_ID), any(Runnable.class),
                eq(NEXT_CAPTURE_TIME));
    }

    private TornRaceDetailVO race(String status, List<TornRaceResultVO> resultList) {
        TornRaceDetailVO race = new TornRaceDetailVO();
        race.setId(1000L);
        race.setTitle(RacingConstants.RACE_TITLE);
        race.setStatus(status);
        race.setResults(resultList);
        return race;
    }

    private TornRaceResultVO result(long driverId) {
        TornRaceResultVO result = new TornRaceResultVO();
        result.setDriverId(driverId);
        result.setPosition(1);
        return result;
    }
}
