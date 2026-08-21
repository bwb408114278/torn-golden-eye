package pn.torn.goldeneye.torn.service.data;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.repository.dao.torn.TornAttackLogDAO;
import pn.torn.goldeneye.repository.model.torn.TornAttackLogDO;
import pn.torn.goldeneye.torn.model.common.TornRespMetaDataVO;
import pn.torn.goldeneye.torn.model.common.TornRepsLinkVO;
import pn.torn.goldeneye.torn.model.torn.attack.AttackLogAttackerVO;
import pn.torn.goldeneye.torn.model.torn.attack.AttackLogDTO;
import pn.torn.goldeneye.torn.model.torn.attack.AttackLogDefenderVO;
import pn.torn.goldeneye.torn.model.torn.attack.AttackLogListVO;
import pn.torn.goldeneye.torn.model.torn.attack.AttackLogRespVO;
import pn.torn.goldeneye.torn.model.torn.attack.AttackLogVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 攻击日志保存服务测试。
 *
 * @author Bai
 * @version 1.3.8
 * @since 2026.08.20
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("攻击日志保存服务测试")
class TornAttackLogServiceTest {

    private static final long FACTION_ID = 9701L;
    private static final long ATTACKER_ID = 2356929L;
    private static final long DEFENDER_ID = 1513571L;
    private static final long PAGE_BASE_TIMESTAMP = 1755100000L;

    @Mock
    private TornApi tornApi;

    @Mock
    private TornAttackLogDAO attackLogDao;

    @Captor
    private ArgumentCaptor<List<TornAttackLogDO>> logListCaptor;

    private ThreadPoolTaskExecutor virtualThreadExecutor;

    @BeforeEach
    void setUp() {
        virtualThreadExecutor = new ThreadPoolTaskExecutor();
        virtualThreadExecutor.setThreadNamePrefix("attack-log-test-");
        virtualThreadExecutor.initialize();
    }

    @AfterEach
    void tearDown() {
        virtualThreadExecutor.shutdown();
    }

    @Test
    @DisplayName("保存超过1000条攻击日志_按1000条分片写入")
    void saveLogData_overThousandLogs_savesInChunksOfThousand() {
        List<TornAttackLogDO> logs = IntStream.range(0, 2001)
                .mapToObj(index -> new TornAttackLogDO())
                .toList();

        buildService().saveLogData(List.of(logs));

        verify(attackLogDao, times(3)).insertIgnoreConflict(logListCaptor.capture());
        List<List<TornAttackLogDO>> batches = logListCaptor.getAllValues();
        assertEquals(List.of(1000, 1000, 1), batches.stream().map(List::size).toList());
    }

    @Test
    @DisplayName("同一来源中相同五字段出现两次时分配occurrence 1和2")
    void saveAttackLog_duplicateFactInSameStream_assignsSequentialOccurrence() {
        stubEmptyExistingLog();
        AttackLogVO missed = buildLogVO(1755100800L, "missed", "GoodLuck missed peterors with her Pillow");
        when(tornApi.sendRequest(eq(FACTION_ID), any(AttackLogDTO.class), eq(AttackLogRespVO.class)))
                .thenReturn(buildResp(List.of(missed, missed), null));

        buildService().saveAttackLog(FACTION_ID, Set.of("3762529d"), Map.of(), Map.of());

        verify(attackLogDao).insertIgnoreConflict(logListCaptor.capture());
        List<TornAttackLogDO> saved = logListCaptor.getValue();
        assertEquals(2, saved.size(), "两条相同五字段合法事件必须全部传给DAO, 不得在service层预丢弃");
        assertEquals(1, saved.get(0).getSourceOccurrence(), "首条相同事实的出现序号必须为1");
        assertEquals(2, saved.get(1).getSourceOccurrence(), "第二条相同事实的出现序号必须为2");
    }

    @Test
    @DisplayName("首页99条且next存在_第二页9条时108条全部落库且occurrence跨页连续")
    void saveAttackLog_paged99And9_writesAllWithContinuousOccurrence() {
        stubEmptyExistingLog();
        long repeatedTimestamp = 1755100900L;
        List<AttackLogVO> firstPage = new ArrayList<>();
        firstPage.add(buildLogVO(repeatedTimestamp, "hit", "rwAttacker hit rwDefender"));
        IntStream.range(0, 98).forEach(index ->
                firstPage.add(buildLogVO(PAGE_BASE_TIMESTAMP + index, "hit", "rwAttacker hit rwDefender")));
        List<AttackLogVO> secondPage = new ArrayList<>();
        secondPage.add(buildLogVO(repeatedTimestamp, "hit", "rwAttacker hit rwDefender"));
        IntStream.range(0, 8).forEach(index ->
                secondPage.add(buildLogVO(1755101000L + index, "hit", "rwAttacker hit rwDefender")));

        when(tornApi.sendRequest(eq(FACTION_ID), any(AttackLogDTO.class), eq(AttackLogRespVO.class)))
                .thenReturn(buildResp(firstPage, "next-link"))
                .thenReturn(buildResp(secondPage, null));

        buildService().saveAttackLog(FACTION_ID, Set.of("3762529d"), Map.of(), Map.of());

        ArgumentCaptor<AttackLogDTO> paramCaptor = ArgumentCaptor.forClass(AttackLogDTO.class);
        verify(tornApi, times(2)).sendRequest(eq(FACTION_ID), paramCaptor.capture(), eq(AttackLogRespVO.class));
        assertEquals(List.of(0, 100), paramCaptor.getAllValues().stream().map(AttackLogDTO::getOffset).toList(),
                "分页offset必须按0、100递增");

        verify(attackLogDao).insertIgnoreConflict(logListCaptor.capture());
        List<TornAttackLogDO> saved = logListCaptor.getValue();
        assertEquals(108, saved.size(), "99+9两页日志必须全部进入落库候选");

        LocalDateTime repeatedTime = DateTimeUtils.convertToDateTime(repeatedTimestamp);
        List<Integer> repeatedOccurrences = saved.stream()
                .filter(log -> repeatedTime.equals(log.getLogTime()))
                .map(TornAttackLogDO::getSourceOccurrence)
                .toList();
        assertEquals(List.of(1, 2), repeatedOccurrences, "同一logId内相同事实的occurrence必须跨页连续递增");
    }

    @Test
    @DisplayName("首页声明next而第二页请求失败时抛BizException且不落库部分数据")
    void saveAttackLog_secondPageFailed_throwsBizExceptionWithoutPartialSave() {
        stubEmptyExistingLog();
        AttackLogRespVO firstPage = buildResp(
                List.of(buildLogVO(PAGE_BASE_TIMESTAMP, "hit", "rwAttacker hit rwDefender")), "next-link");
        when(tornApi.sendRequest(eq(FACTION_ID), any(AttackLogDTO.class), eq(AttackLogRespVO.class)))
                .thenReturn(firstPage)
                .thenReturn(null);

        BizException exception = assertThrows(BizException.class,
                () -> buildService().saveAttackLog(FACTION_ID, Set.of("3762529d"), Map.of(), Map.of()));

        // BizException只填充自身msg字段, 不调用super(msg), 断言须走getMsg()
        assertTrue(exception.getMsg().contains("3762529d"), "异常消息必须包含logId");
        assertTrue(exception.getMsg().contains("offset=100"), "异常消息必须包含失败页offset");
        verify(attackLogDao, never()).insertIgnoreConflict(any());
    }

    @Test
    @DisplayName("不同logId返回共享战斗流时两份来源结果均传给DAO")
    void saveAttackLog_sharedStreamBetweenLogIds_bothSourcesPassedToDao() {
        stubEmptyExistingLog();
        AttackLogRespVO sharedResp = buildResp(List.of(
                buildLogVO(PAGE_BASE_TIMESTAMP, "hit", "rwAttacker hit rwDefender"),
                buildLogVO(PAGE_BASE_TIMESTAMP + 1, "missed", "rwAttacker missed rwDefender"),
                buildLogVO(PAGE_BASE_TIMESTAMP + 2, "won", "rwAttacker won against rwDefender")), null);
        when(tornApi.sendRequest(eq(FACTION_ID), any(AttackLogDTO.class), eq(AttackLogRespVO.class)))
                .thenReturn(sharedResp);

        buildService().saveAttackLog(FACTION_ID, Set.of("3762529d", "843e73fc"), Map.of(), Map.of());

        verify(tornApi, times(2)).sendRequest(eq(FACTION_ID), any(AttackLogDTO.class), eq(AttackLogRespVO.class));
        verify(attackLogDao).insertIgnoreConflict(logListCaptor.capture());
        List<TornAttackLogDO> saved = logListCaptor.getValue();
        assertEquals(6, saved.size(), "两个logId的共享战斗流必须各自完整传给DAO, 不得按战斗指纹整组去重");
        assertEquals(3, saved.stream().filter(log -> "3762529d".equals(log.getLogId())).count(),
                "首个logId来源的三条日志不得被预过滤");
        assertEquals(3, saved.stream().filter(log -> "843e73fc".equals(log.getLogId())).count(),
                "第二个logId来源的三条日志不得被预过滤");
    }

    private TornAttackLogService buildService() {
        return new TornAttackLogService(virtualThreadExecutor, tornApi, attackLogDao);
    }

    private void stubEmptyExistingLog() {
        LambdaQueryChainWrapper<TornAttackLogDO> wrapper = mock(LambdaQueryChainWrapper.class);
        doReturn(wrapper).when(attackLogDao).lambdaQuery();
        doReturn(wrapper).when(wrapper).in(any(), anyCollection());
        doReturn(List.of()).when(wrapper).list();
    }

    private AttackLogRespVO buildResp(List<AttackLogVO> logList, String next) {
        AttackLogListVO attackLog = new AttackLogListVO();
        attackLog.setLog(logList);

        TornRepsLinkVO links = new TornRepsLinkVO();
        links.setNext(next);
        TornRespMetaDataVO metaData = new TornRespMetaDataVO();
        metaData.setLinks(links);

        AttackLogRespVO resp = new AttackLogRespVO();
        resp.setAttackLog(attackLog);
        resp.setMetaData(metaData);
        return resp;
    }

    private AttackLogVO buildLogVO(long timestamp, String action, String text) {
        AttackLogAttackerVO attacker = new AttackLogAttackerVO();
        attacker.setId(ATTACKER_ID);
        attacker.setName("rwAttacker");
        AttackLogDefenderVO defender = new AttackLogDefenderVO();
        defender.setId(DEFENDER_ID);
        defender.setName("rwDefender");

        AttackLogVO logVO = new AttackLogVO();
        logVO.setText(text);
        logVO.setTimestamp(timestamp);
        logVO.setAction(action);
        logVO.setIcon("icon");
        logVO.setAttacker(attacker);
        logVO.setDefender(defender);
        return logVO;
    }
}
