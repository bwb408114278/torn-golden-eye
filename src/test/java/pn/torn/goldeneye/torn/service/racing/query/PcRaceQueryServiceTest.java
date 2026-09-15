package pn.torn.goldeneye.torn.service.racing.query;

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
import pn.torn.goldeneye.repository.model.racing.TornRacingParticipantDO;
import pn.torn.goldeneye.repository.model.racing.TornRacingRaceDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.model.racing.view.PcRaceParticipantVO;
import pn.torn.goldeneye.torn.model.racing.view.PcRaceResultBO;
import pn.torn.goldeneye.torn.model.racing.view.PcRaceScoreBO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PC赛车榜单与个人成绩口径测试。
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PC赛车榜单查询测试")
class PcRaceQueryServiceTest {
    private static final long RACE_ID = 1000L;
    /**
     * 北京2026-01-05 00:30，业务日期为2026-01-04
     */
    private static final long RACE_START_TIMESTAMP = 1767544200L;
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 1, 4);
    private static final long ALLIANCE_FACTION_ID = 20465L;
    private static final long OUTSIDER_FACTION_ID = 999L;

    @Mock
    private TornRacingRaceDAO raceDao;
    @Mock
    private TornRacingParticipantDAO participantDao;
    @Mock
    private TornSettingFactionManager factionManager;
    @Mock
    private PcRaceDrawCalculator drawCalculator;

    private PcRaceQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = new PcRaceQueryService(raceDao, participantDao, factionManager, drawCalculator);
    }

    @Test
    @DisplayName("SMTH名次为联盟未撞车内按原始名次的序号，撞车行置底且名次为空")
    void buildResultByRaceId_shouldRankAllianceUncrashedAndPushCrashedToBottom() {
        stubResult(List.of(
                withLap(participant(RACE_ID, 1L, ALLIANCE_FACTION_ID, false, 2), "30.00", "31.00"),
                withLap(participant(RACE_ID, 2L, ALLIANCE_FACTION_ID, false, 1), "17536.00", "26.45"),
                participant(RACE_ID, 3L, ALLIANCE_FACTION_ID, true, null),
                withLap(participant(RACE_ID, 4L, ALLIANCE_FACTION_ID, false, 3), "40.00", null),
                withLap(participant(RACE_ID, 5L, OUTSIDER_FACTION_ID, false, 4), "20.00", "20.00")));

        PcRaceResultBO result = queryService.buildResultByRaceId(RACE_ID);

        assertNotNull(result);
        assertEquals(List.of(2L, 1L, 4L, 3L),
                result.participants().stream().map(PcRaceParticipantVO::userId).toList());
        assertEquals(Arrays.asList(1, 2, 3, null),
                result.participants().stream().map(PcRaceParticipantVO::smthRank).toList());
        assertEquals("04:52:16.00", result.participants().getFirst().raceTimeText());
        assertEquals("00:26.45", result.participants().getFirst().bestLapTimeText());
        assertNull(result.participants().get(2).bestLapTimeText());
        assertTrue(result.participants().get(3).crashed());
        assertNull(result.participants().get(3).smthRank());
        assertEquals("PHN", result.participants().getFirst().factionShortName());
        assertEquals("PHN", result.participants().get(3).factionShortName());
        assertEquals(2L, result.fastestLap().userId());
    }

    @Test
    @DisplayName("最快圈排除撞车与非联盟选手的圈速")
    void buildResultByRaceId_shouldPickFastestLapAmongUncrashedAlliance() {
        stubResult(List.of(
                withLap(participant(RACE_ID, 1L, ALLIANCE_FACTION_ID, false, 1), "30.00", "26.45"),
                withLap(participant(RACE_ID, 2L, ALLIANCE_FACTION_ID, true, null), null, "10.00"),
                withLap(participant(RACE_ID, 3L, OUTSIDER_FACTION_ID, false, 2), "20.00", "5.00")));

        PcRaceResultBO result = queryService.buildResultByRaceId(RACE_ID);

        assertNotNull(result.fastestLap());
        assertEquals(1L, result.fastestLap().userId());
    }

    @Test
    @DisplayName("参赛率分子分母均含撞车选手")
    void buildResultByRaceId_shouldCalculateAllianceRateIncludingCrashed() {
        stubResult(List.of(
                withLap(participant(RACE_ID, 1L, ALLIANCE_FACTION_ID, false, 1), "30.00", "26.45"),
                withLap(participant(RACE_ID, 2L, ALLIANCE_FACTION_ID, false, 2), "31.00", "27.00"),
                participant(RACE_ID, 3L, ALLIANCE_FACTION_ID, true, null),
                withLap(participant(RACE_ID, 4L, OUTSIDER_FACTION_ID, false, 3), "20.00", "20.00"),
                withLap(participant(RACE_ID, 5L, OUTSIDER_FACTION_ID, false, 4), "21.00", "21.00")));

        PcRaceResultBO result = queryService.buildResultByRaceId(RACE_ID);

        assertEquals(3, result.allianceCount());
        assertEquals(5, result.totalCount());
        assertEquals(new BigDecimal("60.00"), result.allianceRate());
    }

    @Test
    @DisplayName("抽奖使用未撞车的联盟选手且保持展示顺序")
    void buildResultByRaceId_shouldDelegateDrawWithUncrashedAlliancePool() {
        stubResult(List.of(
                withLap(participant(RACE_ID, 1L, ALLIANCE_FACTION_ID, false, 2), "30.00", "31.00"),
                withLap(participant(RACE_ID, 2L, ALLIANCE_FACTION_ID, false, 1), "17536.00", "26.45"),
                participant(RACE_ID, 3L, ALLIANCE_FACTION_ID, true, null),
                withLap(participant(RACE_ID, 4L, ALLIANCE_FACTION_ID, false, 3), "40.00", null)));

        queryService.buildResultByRaceId(RACE_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PcRaceParticipantVO>> captor = ArgumentCaptor.forClass(List.class);
        verify(drawCalculator).draw(eq(RACE_ID), captor.capture());
        assertEquals(List.of(2L, 1L, 4L),
                captor.getValue().stream().map(PcRaceParticipantVO::userId).toList());
    }

    @Test
    @DisplayName("业务日期无赛事时返回null")
    void buildResultByBusinessDate_shouldReturnNullWhenRaceMissing() {
        assertNull(queryService.buildResultByBusinessDate(BUSINESS_DATE));
    }

    @Test
    @DisplayName("个人成绩回算各场SMTH名次并按开赛时间降序")
    void buildScore_shouldRebuildSmthRankAndOrderByStartTimeDesc() {
        long laterRaceId = RACE_ID + 1;
        when(participantDao.queryRecentAllianceByUser(9L, RacingConstants.SCORE_HISTORY_LIMIT)).thenReturn(List.of(
                participant(laterRaceId, 9L, ALLIANCE_FACTION_ID, false, 1),
                participant(RACE_ID, 9L, ALLIANCE_FACTION_ID, false, 2)));
        when(raceDao.queryByRaceIdList(anyList())).thenReturn(List.of(
                race(RACE_ID, RACE_START_TIMESTAMP), race(laterRaceId, RACE_START_TIMESTAMP + 86400L)));
        when(participantDao.queryByRaceIdList(anyList())).thenReturn(List.of(
                participant(RACE_ID, 9L, ALLIANCE_FACTION_ID, false, 2),
                participant(RACE_ID, 8L, ALLIANCE_FACTION_ID, false, 1),
                participant(laterRaceId, 9L, ALLIANCE_FACTION_ID, false, 1),
                participant(laterRaceId, 8L, ALLIANCE_FACTION_ID, false, 2)));
        when(factionManager.getIdMap()).thenReturn(Map.of(ALLIANCE_FACTION_ID, faction()));

        PcRaceScoreBO score = queryService.buildScore(9L);

        assertEquals(9L, score.userId());
        assertEquals(2, score.items().size());
        assertEquals(BUSINESS_DATE.plusDays(1), score.items().getFirst().businessDate());
        assertEquals(BUSINESS_DATE, score.items().get(1).businessDate());
        assertEquals(List.of(1, 2),
                score.items().stream().map(item -> item.participant().smthRank()).toList());
    }

    private void stubResult(List<TornRacingParticipantDO> participantList) {
        when(raceDao.queryByRaceId(RACE_ID)).thenReturn(race(RACE_ID, RACE_START_TIMESTAMP));
        when(participantDao.queryByRaceId(RACE_ID)).thenReturn(participantList);
        when(factionManager.getIdMap()).thenReturn(Map.of(ALLIANCE_FACTION_ID, faction()));
        when(drawCalculator.draw(eq(RACE_ID), anyList())).thenReturn(null);
    }

    private TornRacingRaceDO race(long raceId, long startTimestamp) {
        TornRacingRaceDO race = new TornRacingRaceDO();
        race.setRaceId(raceId);
        race.setBusinessDate(BUSINESS_DATE.plusDays(raceId - RACE_ID));
        race.setStartTime(DateTimeUtils.convertToDateTime(startTimestamp));
        race.setCapturedTime(LocalDateTime.of(2026, 1, 5, 8, 30));
        return race;
    }

    private TornRacingParticipantDO participant(long raceId, long userId, long factionId, boolean crashed,
                                                Integer position) {
        TornRacingParticipantDO participant = new TornRacingParticipantDO();
        participant.setRaceId(raceId);
        participant.setUserId(userId);
        participant.setNickname("选手" + userId);
        participant.setFactionId(factionId);
        participant.setIsAlliance(factionId == ALLIANCE_FACTION_ID);
        participant.setHasCrashed(crashed);
        participant.setPosition(position);
        return participant;
    }

    private TornRacingParticipantDO withLap(TornRacingParticipantDO participant, String raceTime,
                                            String bestLapTime) {
        participant.setRaceTime(raceTime == null ? null : new BigDecimal(raceTime));
        participant.setBestLapTime(bestLapTime == null ? null : new BigDecimal(bestLapTime));
        return participant;
    }

    private TornSettingFactionDO faction() {
        TornSettingFactionDO faction = new TornSettingFactionDO();
        faction.setId(ALLIANCE_FACTION_ID);
        faction.setFactionShortName("PHN");
        return faction;
    }
}
