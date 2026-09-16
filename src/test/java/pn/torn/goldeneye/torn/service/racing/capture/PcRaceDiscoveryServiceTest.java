package pn.torn.goldeneye.torn.service.racing.capture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.constants.torn.RacingConstants;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.dao.racing.TornRacingParticipantDAO;
import pn.torn.goldeneye.repository.dao.racing.TornRacingRaceDAO;
import pn.torn.goldeneye.repository.model.racing.TornRacingParticipantDO;
import pn.torn.goldeneye.repository.model.racing.TornRacingRaceDO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.torn.model.user.racing.TornRaceDetailVO;
import pn.torn.goldeneye.torn.model.user.racing.TornRaceScheduleVO;
import pn.torn.goldeneye.torn.model.user.racing.TornUserRaceDTO;
import pn.torn.goldeneye.torn.model.user.racing.TornUserRacesVO;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PC赛车赛事定位链测试。
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PC赛车赛事定位测试")
class PcRaceDiscoveryServiceTest {
    /**
     * 北京2026-01-05 00:30对应的秒级时间戳，其业务日期为2026-01-04
     */
    private static final long RACE_START_TIMESTAMP = 1767544200L;
    /**
     * 前一天的同一时刻，用于构造不匹配的业务日期
     */
    private static final long PREVIOUS_DAY_START_TIMESTAMP = 1767457800L;
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 1, 4);
    private static final long RACE_ID = 1000L;
    private static final long FALLBACK_USER_ID = 500L;

    @Mock
    private TornApi tornApi;
    @Mock
    private TornApiKeyConfig apiKeyConfig;
    @Mock
    private TornRacingRaceDAO raceDao;
    @Mock
    private TornRacingParticipantDAO participantDao;

    private PcRaceDiscoveryService discoveryService;

    @BeforeEach
    void setUp() {
        discoveryService = new PcRaceDiscoveryService(tornApi, apiKeyConfig, raceDao, participantDao);
    }

    @Test
    @DisplayName("候选链按创建人、近期选手、主力帮派、其余Key持有人保序去重")
    void buildCandidateUserIds_shouldFollowPriorityOrder() {
        TornRacingRaceDO latestRace = new TornRacingRaceDO();
        latestRace.setRaceId(RACE_ID);
        when(raceDao.queryLatestRace()).thenReturn(latestRace);
        when(participantDao.queryByRaceId(RACE_ID)).thenReturn(List.of(
                participant(11L, 2), participant(12L, 1), participant(13L, null)));
        when(apiKeyConfig.getAllEnableKeys()).thenReturn(List.of(
                apiKey(TornConstants.FACTION_PN_ID, 200L),
                apiKey(TornConstants.FACTION_CCRC_ID, 300L),
                apiKey(999L, FALLBACK_USER_ID),
                apiKey(TornConstants.FACTION_PN_ID, 200L)));

        List<Long> candidateList = discoveryService.buildCandidateUserIds();

        assertEquals(List.of(RacingConstants.CREATOR_USER_ID, 12L, 11L, 13L, 200L, 300L, FALLBACK_USER_ID),
                candidateList);
    }

    @Test
    @DisplayName("候选列表按扫描上限截断")
    void buildCandidateUserIds_shouldTruncateToScanLimit() {
        List<TornApiKeyDO> keyList = new ArrayList<>();
        for (int index = 0; index < RacingConstants.DISCOVERY_SCAN_LIMIT + 10; index++) {
            keyList.add(apiKey(999L, 10_000L + index));
        }
        when(apiKeyConfig.getAllEnableKeys()).thenReturn(keyList);

        List<Long> candidateList = discoveryService.buildCandidateUserIds();

        assertEquals(RacingConstants.DISCOVERY_SCAN_LIMIT, candidateList.size());
    }

    @Test
    @DisplayName("命中即停返回完整赛事，请求参数含自定义赛过滤且Key被归还")
    void findRace_shouldStopOnHitAndReturnKey() {
        TornApiKeyDO creatorKey = apiKey(999L, RacingConstants.CREATOR_USER_ID);
        when(apiKeyConfig.getAllEnableKeys()).thenReturn(List.of(apiKey(999L, FALLBACK_USER_ID)));
        when(apiKeyConfig.getKeyByUserId(RacingConstants.CREATOR_USER_ID)).thenReturn(creatorKey);
        when(tornApi.sendRequest(any(TornUserRaceDTO.class), eq(creatorKey), eq(TornUserRacesVO.class)))
                .thenReturn(races(race(RACE_ID, "smthpc", RACE_START_TIMESTAMP)));

        TornRaceDetailVO result = discoveryService.findRace(BUSINESS_DATE);

        assertNotNull(result);
        assertEquals(RACE_ID, result.getId());
        verify(apiKeyConfig, times(1)).getKeyByUserId(anyLong());
        verify(apiKeyConfig).returnKey(creatorKey);
        ArgumentCaptor<TornUserRaceDTO> captor = ArgumentCaptor.forClass(TornUserRaceDTO.class);
        verify(tornApi).sendRequest(captor.capture(), eq(creatorKey), eq(TornUserRacesVO.class));
        assertEquals(RacingConstants.RACE_CATEGORY_CUSTOM, captor.getValue().buildReqParam().getFirst("cat"));
        assertEquals(String.valueOf(RacingConstants.DISCOVERY_PAGE_SIZE),
                captor.getValue().buildReqParam().getFirst("limit"));
    }

    @Test
    @DisplayName("业务日期不匹配时全部候选未命中返回null并归还Key")
    void findRace_shouldReturnNullWhenNoCandidateHit() {
        TornApiKeyDO creatorKey = apiKey(999L, RacingConstants.CREATOR_USER_ID);
        when(apiKeyConfig.getAllEnableKeys()).thenReturn(List.of(apiKey(999L, FALLBACK_USER_ID)));
        when(apiKeyConfig.getKeyByUserId(RacingConstants.CREATOR_USER_ID)).thenReturn(creatorKey);
        when(tornApi.sendRequest(any(TornUserRaceDTO.class), eq(creatorKey), eq(TornUserRacesVO.class)))
                .thenReturn(races(race(RACE_ID, RacingConstants.RACE_TITLE, PREVIOUS_DAY_START_TIMESTAMP)));

        TornRaceDetailVO result = discoveryService.findRace(BUSINESS_DATE);

        assertNull(result);
        verify(apiKeyConfig).returnKey(creatorKey);
        verify(apiKeyConfig).getKeyByUserId(FALLBACK_USER_ID);
    }

    @Test
    @DisplayName("单候选异常不中断定位链且仍然归还Key")
    void findRace_shouldContinueAfterCandidateException() {
        TornApiKeyDO creatorKey = apiKey(999L, RacingConstants.CREATOR_USER_ID);
        TornApiKeyDO fallbackKey = apiKey(999L, FALLBACK_USER_ID);
        when(apiKeyConfig.getAllEnableKeys()).thenReturn(List.of(apiKey(999L, FALLBACK_USER_ID)));
        when(apiKeyConfig.getKeyByUserId(RacingConstants.CREATOR_USER_ID)).thenReturn(creatorKey);
        when(apiKeyConfig.getKeyByUserId(FALLBACK_USER_ID)).thenReturn(fallbackKey);
        when(tornApi.sendRequest(any(TornUserRaceDTO.class), eq(creatorKey), eq(TornUserRacesVO.class)))
                .thenThrow(new IllegalStateException("候选调用失败"));
        when(tornApi.sendRequest(any(TornUserRaceDTO.class), eq(fallbackKey), eq(TornUserRacesVO.class)))
                .thenReturn(races(race(RACE_ID, RacingConstants.RACE_TITLE, RACE_START_TIMESTAMP)));

        TornRaceDetailVO result = discoveryService.findRace(BUSINESS_DATE);

        assertNotNull(result);
        verify(apiKeyConfig).returnKey(creatorKey);
        verify(apiKeyConfig).returnKey(fallbackKey);
    }

    private TornApiKeyDO apiKey(Long factionId, long userId) {
        TornApiKeyDO key = new TornApiKeyDO();
        key.setId(userId);
        key.setUserId(userId);
        key.setFactionId(factionId);
        return key;
    }

    private TornRacingParticipantDO participant(long userId, Integer position) {
        TornRacingParticipantDO participant = new TornRacingParticipantDO();
        participant.setUserId(userId);
        participant.setPosition(position);
        return participant;
    }

    private TornUserRacesVO races(TornRaceDetailVO race) {
        TornUserRacesVO races = new TornUserRacesVO();
        races.setRaces(List.of(race));
        return races;
    }

    private TornRaceDetailVO race(long raceId, String title, Long start) {
        TornRaceScheduleVO schedule = new TornRaceScheduleVO();
        schedule.setStart(start);
        TornRaceDetailVO race = new TornRaceDetailVO();
        race.setId(raceId);
        race.setTitle(title);
        race.setStatus(RacingConstants.RACE_FINISHED_STATUS);
        race.setSchedule(schedule);
        return race;
    }
}
