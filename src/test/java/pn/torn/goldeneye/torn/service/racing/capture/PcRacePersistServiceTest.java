package pn.torn.goldeneye.torn.service.racing.capture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.RacingConstants;
import pn.torn.goldeneye.repository.dao.racing.TornRacingParticipantDAO;
import pn.torn.goldeneye.repository.dao.racing.TornRacingRaceDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.racing.TornRacingParticipantDO;
import pn.torn.goldeneye.repository.model.racing.TornRacingRaceDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.model.user.racing.TornRaceDetailVO;
import pn.torn.goldeneye.torn.model.user.racing.TornRaceResultVO;
import pn.torn.goldeneye.torn.model.user.racing.TornRaceScheduleVO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * PC赛车落库测试。
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PC赛车落库测试")
class PcRacePersistServiceTest {
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 1, 4);
    private static final long RACE_ID = 1000L;
    /**
     * 北京2026-01-05 00:30对应的秒级时间戳
     */
    private static final long RACE_START_TIMESTAMP = 1767544200L;
    private static final long ALLIANCE_FACTION_ID = 20465L;
    /**
     * Torn赛道ID 10 = Docks
     */
    private static final int TRACK_ID = 10;

    @Mock
    private TornRacingRaceDAO raceDao;
    @Mock
    private TornRacingParticipantDAO participantDao;
    @Mock
    private TornUserDAO userDao;
    @Mock
    private TornSettingFactionManager factionManager;

    private PcRacePersistService persistService;

    @BeforeEach
    void setUp() {
        persistService = new PcRacePersistService(raceDao, participantDao, userDao, factionManager);
    }

    @Test
    @DisplayName("主行写入赛事快照、业务日期与抓取时间")
    void save_shouldWriteRaceSnapshot() {
        when(userDao.queryUserMap(any())).thenReturn(Map.of());
        when(factionManager.getIdMap()).thenReturn(Map.of());

        persistService.save(race(List.of(result(1L))), BUSINESS_DATE);

        ArgumentCaptor<TornRacingRaceDO> captor = ArgumentCaptor.forClass(TornRacingRaceDO.class);
        verify(raceDao).save(captor.capture());
        TornRacingRaceDO raceDO = captor.getValue();
        assertEquals(RACE_ID, raceDO.getRaceId());
        assertEquals(RacingConstants.RACE_TITLE, raceDO.getTitle());
        assertEquals("Docks", raceDO.getTrackName());
        assertEquals(BUSINESS_DATE, raceDO.getBusinessDate());
        assertEquals(RacingConstants.RACE_FINISHED_STATUS, raceDO.getStatus());
        assertEquals(LocalDateTime.of(2026, 1, 5, 0, 30), raceDO.getStartTime());
        assertNotNull(raceDO.getCapturedTime());
        verify(participantDao).saveBatch(anyList());
    }

    @Test
    @DisplayName("家族判定与昵称帮派均取抓取时刻快照")
    void save_shouldSnapshotAllianceFields() {
        TornUserDO allianceUser = user(1L, "家族选手", ALLIANCE_FACTION_ID);
        TornUserDO outsider = user(2L, "外部选手", 999L);
        when(userDao.queryUserMap(any())).thenReturn(Map.of(1L, allianceUser, 2L, outsider));
        when(factionManager.getIdMap()).thenReturn(Map.of(ALLIANCE_FACTION_ID, new TornSettingFactionDO()));

        persistService.save(race(List.of(result(1L), result(2L), result(3L))), BUSINESS_DATE);

        List<TornRacingParticipantDO> participantList = captureParticipants();
        assertEquals(3, participantList.size());
        TornRacingParticipantDO alliance = participantList.get(0);
        assertTrue(alliance.getIsAlliance());
        assertEquals("家族选手", alliance.getNickname());
        assertEquals(ALLIANCE_FACTION_ID, alliance.getFactionId());
        TornRacingParticipantDO local = participantList.get(1);
        assertFalse(local.getIsAlliance());
        assertEquals("外部选手", local.getNickname());
        assertEquals(999L, local.getFactionId());
        TornRacingParticipantDO unknown = participantList.get(2);
        assertFalse(unknown.getIsAlliance());
        assertNull(unknown.getNickname());
        assertNull(unknown.getFactionId());
    }

    @Test
    @DisplayName("撞车标记、名次与用时圈速按原始值落库")
    void save_shouldMapCrashAndTimeFields() {
        TornRaceResultVO crashed = result(1L);
        crashed.setPosition(null);
        crashed.setHasCrashed(true);
        TornRaceResultVO finished = result(2L);
        finished.setPosition(3);
        finished.setHasCrashed(false);
        finished.setRaceTime(new BigDecimal("292.16"));
        finished.setBestLapTime(new BigDecimal("26.45"));
        when(userDao.queryUserMap(any())).thenReturn(Map.of());
        when(factionManager.getIdMap()).thenReturn(Map.of());

        persistService.save(race(List.of(crashed, finished)), BUSINESS_DATE);

        List<TornRacingParticipantDO> participantList = captureParticipants();
        assertTrue(participantList.get(0).getHasCrashed());
        assertNull(participantList.get(0).getRaceTime());
        assertFalse(participantList.get(1).getHasCrashed());
        assertEquals(new BigDecimal("292.16"), participantList.get(1).getRaceTime());
        assertEquals(new BigDecimal("26.45"), participantList.get(1).getBestLapTime());
        assertEquals(Integer.valueOf(3), participantList.get(1).getPosition());
    }

    @Test
    @DisplayName("落库为纯插入，无删除调用")
    void save_shouldInsertOnly() {
        when(userDao.queryUserMap(any())).thenReturn(Map.of());
        when(factionManager.getIdMap()).thenReturn(Map.of());

        persistService.save(race(List.of(result(1L))), BUSINESS_DATE);

        verify(raceDao).save(any(TornRacingRaceDO.class));
        verify(participantDao).saveBatch(anyList());
        verifyNoMoreInteractions(raceDao, participantDao);
    }

    private List<TornRacingParticipantDO> captureParticipants() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TornRacingParticipantDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(participantDao).saveBatch(captor.capture());
        return captor.getValue();
    }

    private TornRaceDetailVO race(List<TornRaceResultVO> resultList) {
        TornRaceScheduleVO schedule = new TornRaceScheduleVO();
        schedule.setStart(RACE_START_TIMESTAMP);
        schedule.setEnd(RACE_START_TIMESTAMP + 3600L);
        TornRaceDetailVO race = new TornRaceDetailVO();
        race.setId(RACE_ID);
        race.setTitle(RacingConstants.RACE_TITLE);
        race.setTrackId(TRACK_ID);
        race.setStatus(RacingConstants.RACE_FINISHED_STATUS);
        race.setSchedule(schedule);
        race.setResults(resultList);
        return race;
    }

    private TornRaceResultVO result(long driverId) {
        TornRaceResultVO result = new TornRaceResultVO();
        result.setDriverId(driverId);
        return result;
    }

    private TornUserDO user(long userId, String nickname, long factionId) {
        TornUserDO user = new TornUserDO();
        user.setId(userId);
        user.setNickname(nickname);
        user.setFactionId(factionId);
        return user;
    }
}
