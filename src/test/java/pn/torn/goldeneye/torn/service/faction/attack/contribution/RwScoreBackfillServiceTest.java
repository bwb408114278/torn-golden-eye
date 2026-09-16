package pn.torn.goldeneye.torn.service.faction.attack.contribution;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.base.torn.TornReqParamV2;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwDAO;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwRankDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwRankDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.model.faction.rw.TornFactionRwDTO;
import pn.torn.goldeneye.torn.model.faction.rw.TornFactionRwFactionVO;
import pn.torn.goldeneye.torn.model.faction.rw.TornFactionRwRespVO;
import pn.torn.goldeneye.torn.model.faction.rw.TornFactionRwVO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RW真赛比分回填服务测试。
 *
 * <p>只验证幂等填充、翻页终止条件与结算回放触发条件，不重复名次口径与SQL边界。</p>
 *
 * @author Bai
 * @version 1.6.4
 * @since 2026.09.16
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RW真赛比分回填服务测试")
class RwScoreBackfillServiceTest {
    private static final long FACTION_ID = 20465L;
    private static final long SELF_FACTION_ID = FACTION_ID;
    private static final long OPPONENT_FACTION_ID = 231L;
    private static final String FACTION_SHORT_NAME = "PHN";
    private static final LocalDateTime EARLIEST_START = LocalDateTime.of(2025, 9, 4, 13, 0);

    @Mock
    private TornApi tornApi;
    @Mock
    private TornFactionRwDAO rwDao;
    @Mock
    private TornFactionRwRankDAO rankDao;
    @Mock
    private RwRankSettleService rankSettleService;
    @Mock
    private TornSettingFactionManager factionManager;

    private RwScoreBackfillService backfillService;

    @BeforeEach
    void setUp() {
        backfillService = new RwScoreBackfillService(tornApi, rwDao, rankDao, rankSettleService, factionManager);
        when(factionManager.getIdMap()).thenReturn(Map.of(FACTION_ID, faction()));
    }

    @Test
    @DisplayName("库内已有比分时重跑不覆盖任何列")
    void backfillAll_existingValues_neverOverwrites() {
        TornFactionRwDO filled = war(48522L, LocalDateTime.of(2026, 9, 4, 21, 0));
        filled.setTargetScore(5000);
        filled.setWinnerFactionId(OPPONENT_FACTION_ID);
        filled.setFactionScore(100);
        filled.setOpponentScore(22625);
        filled.setOpponentShortName("DA");
        stubWarQuery(List.of(filled));
        stubApiPages(Map.of(0, page(apiWar(48522L, LocalDateTime.of(2026, 9, 4, 21, 0), 99999))));
        stubSettledRanks(List.of());

        String summary = backfillService.backfillAll();

        verify(rwDao, never()).lambdaUpdate();
        assertTrue(summary.contains(FACTION_SHORT_NAME + "：匹配1场，更新0场"), summary);
    }

    @Test
    @DisplayName("库内比分为空时补齐全部五列并触发结算回放")
    void backfillAll_nullColumns_areFilledOnce() {
        TornFactionRwDO empty = war(48522L, LocalDateTime.of(2026, 9, 4, 21, 0));
        stubWarQuery(List.of(empty));
        stubApiPages(Map.of(0, page(apiWar(48522L, LocalDateTime.of(2026, 9, 4, 21, 0), 22625))));
        LambdaUpdateChainWrapper<TornFactionRwDO> update = stubUpdate();
        stubSettledRanks(List.of());

        String summary = backfillService.backfillAll();

        verify(update, times(5)).set(eq(true), any(SFunction.class), any());
        verify(rankSettleService).settle(any(TornFactionRwDO.class));
        assertTrue(summary.contains(FACTION_SHORT_NAME + "：匹配1场，更新1场，结算回放1场"), summary);
    }

    @Test
    @DisplayName("本页最旧场次覆盖库内最早场次后停止翻页")
    void backfillAll_stopsPagingWhenOldestPageWarCoversEarliestDbWar() {
        TornFactionRwDO earliest = war(30329L, EARLIEST_START);
        stubWarQuery(List.of(earliest));
        List<Integer> offsets = new ArrayList<>();
        when(tornApi.sendRequest(anyLong(), any(TornReqParamV2.class), eq(TornFactionRwRespVO.class)))
                .thenAnswer(invocation -> {
                    TornFactionRwDTO dto = invocation.getArgument(1);
                    offsets.add(dto.getOffset());
                    if (dto.getOffset() == 0) {
                        return page(apiWar(48522L, LocalDateTime.of(2026, 9, 4, 21, 0), 22625),
                                apiWar(40404L, LocalDateTime.of(2026, 4, 20, 21, 0), 15000));
                    }
                    return page(apiWar(30329L, EARLIEST_START, 12000));
                });
        stubUpdate();
        stubSettledRanks(List.of());

        backfillService.backfillAll();

        assertEquals(List.of(0, 100), offsets, "覆盖到库内最早场次后必须停止翻页");
    }

    @Test
    @DisplayName("只对已结束且对手得分大于3000且缺少结算行的场次回放结算")
    void backfillAll_replaysSettleOnlyForMissingQualifiedWars() {
        TornFactionRwDO missing = war(48522L, LocalDateTime.of(2026, 9, 4, 21, 0));
        missing.setOpponentScore(22625);
        TornFactionRwDO lowScore = war(46672L, LocalDateTime.of(2026, 7, 31, 21, 0));
        lowScore.setOpponentScore(3000);
        TornFactionRwDO settled = war(44155L, LocalDateTime.of(2026, 6, 19, 9, 0));
        settled.setOpponentScore(16126);
        TornFactionRwDO running = war(49000L, LocalDateTime.of(2026, 9, 10, 21, 0));
        running.setOpponentScore(18000);
        running.setEndTime(null);
        stubWarQuery(List.of(missing, lowScore, settled, running));
        stubApiPages(Map.of(0, page()));
        stubSettledRanks(List.of(rank(44155L)));

        String summary = backfillService.backfillAll();

        verify(rankSettleService).settle(any(TornFactionRwDO.class));
        assertTrue(summary.contains("结算回放1场"), summary);
    }

    private LambdaUpdateChainWrapper<TornFactionRwDO> stubUpdate() {
        LambdaUpdateChainWrapper<TornFactionRwDO> update = mock(LambdaUpdateChainWrapper.class);
        when(rwDao.lambdaUpdate()).thenReturn(update);
        when(update.set(anyBoolean(), any(SFunction.class), any())).thenReturn(update);
        when(update.eq(any(SFunction.class), any())).thenReturn(update);
        when(update.update()).thenReturn(true);
        return update;
    }

    private void stubWarQuery(List<TornFactionRwDO> wars) {
        LambdaQueryChainWrapper<TornFactionRwDO> query = mock(LambdaQueryChainWrapper.class);
        when(rwDao.lambdaQuery()).thenReturn(query);
        when(query.eq(any(SFunction.class), any())).thenReturn(query);
        when(query.list()).thenReturn(wars);
    }

    private void stubSettledRanks(List<TornFactionRwRankDO> ranks) {
        LambdaQueryChainWrapper<TornFactionRwRankDO> query = mock(LambdaQueryChainWrapper.class);
        when(rankDao.lambdaQuery()).thenReturn(query);
        when(query.in(any(SFunction.class), anyCollection())).thenReturn(query);
        when(query.list()).thenReturn(ranks);
    }

    private void stubApiPages(Map<Integer, TornFactionRwRespVO> pageByOffset) {
        when(tornApi.sendRequest(anyLong(), any(TornReqParamV2.class), eq(TornFactionRwRespVO.class)))
                .thenAnswer(invocation -> {
                    TornFactionRwDTO dto = invocation.getArgument(1);
                    return pageByOffset.getOrDefault(dto.getOffset(), page());
                });
    }

    private TornSettingFactionDO faction() {
        TornSettingFactionDO faction = new TornSettingFactionDO();
        faction.setId(FACTION_ID);
        faction.setFactionShortName(FACTION_SHORT_NAME);
        return faction;
    }

    private TornFactionRwDO war(long rwId, LocalDateTime startTime) {
        TornFactionRwDO war = new TornFactionRwDO();
        war.setId(rwId);
        war.setFactionId(FACTION_ID);
        war.setStartTime(startTime);
        war.setEndTime(startTime.plusDays(1));
        return war;
    }

    private TornFactionRwVO apiWar(long rwId, LocalDateTime startTime, int opponentScore) {
        TornFactionRwVO war = new TornFactionRwVO();
        war.setId(rwId);
        war.setStart(startTime.atZone(java.time.ZoneOffset.UTC).toEpochSecond() - 8 * 3600L);
        war.setTarget(5000);
        war.setWinner(OPPONENT_FACTION_ID);
        war.setFactions(List.of(apiFaction(SELF_FACTION_ID, "SMTH - Phoenix Nirvana", 100),
                apiFaction(OPPONENT_FACTION_ID, "Destructive Anomaly", opponentScore)));
        return war;
    }

    private TornFactionRwFactionVO apiFaction(long factionId, String name, int score) {
        TornFactionRwFactionVO faction = new TornFactionRwFactionVO();
        faction.setId(factionId);
        faction.setName(name);
        faction.setScore(score);
        return faction;
    }

    private TornFactionRwRankDO rank(long rwId) {
        TornFactionRwRankDO rank = new TornFactionRwRankDO();
        rank.setRwId(rwId);
        rank.setUserId(1001L);
        rank.setNickname("Cinderine");
        rank.setRankNum(1);
        return rank;
    }

    private TornFactionRwRespVO page(TornFactionRwVO... wars) {
        TornFactionRwRespVO resp = new TornFactionRwRespVO();
        resp.setRwList(List.of(wars));
        return resp;
    }
}
