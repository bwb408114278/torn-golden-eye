package pn.torn.goldeneye.repository.mapper.faction.attack;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionAttackDAO;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwStatWindowDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionAttackDO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwStatWindowDO;
import pn.torn.goldeneye.torn.model.faction.attack.RwStatWindowVO;
import pn.torn.goldeneye.torn.model.faction.attack.RwUserAttackStatVO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RW对冲窗口真实 PostgreSQL Mapper 测试。
 *
 * <p>验证窗口目录、最近已确认窗口、双方用户聚合与第三方帮派攻击过滤，
 * 使用2099严格未来时间和测试专用ID命名空间隔离真实数据，事务回滚保证零残留。</p>
 *
 * @author Bai
 * @version 1.4.4
 * @since 2026.08.24
 */
@SpringBootTest
@Tag("shared-db")
@Transactional
@Rollback
@DisplayName("RW对冲窗口Mapper真实PostgreSQL测试")
class TornFactionRwStatWindowMapperTest {
    private static final long RW_ID = 9_999_001L;
    private static final long SELF_FACTION = 9_999_101L;
    private static final long OPPONENT_FACTION = 9_999_102L;
    private static final long THIRD_FACTION = 9_999_103L;
    private static final long SELF_USER_1 = 9_999_201L;
    private static final long SELF_USER_3 = 9_999_203L;
    private static final long OPPONENT_USER_1 = 9_999_301L;
    private static final long OPPONENT_USER_4 = 9_999_304L;
    private static final long THIRD_USER = 9_999_401L;
    private static final LocalDateTime BASE = LocalDateTime.of(2099, 1, 1, 12, 0);

    @Autowired
    private TornFactionRwStatWindowDAO windowDao;

    @Autowired
    private TornFactionAttackDAO attackDao;

    @Test
    @DisplayName("窗口目录与最近确认窗口只统计当前RW双方并排除第三方攻击")
    void queryCatalogAndLatestConfirmedWindow_filterThirdPartyByFactionPair() {
        insertWindow("A", BASE, BASE.plusSeconds(150), true);
        insertWindow("B", BASE.plusMinutes(10), BASE.plusMinutes(10).plusSeconds(150), true);
        insertWindow("C", BASE.plusMinutes(20), BASE.plusMinutes(20).plusSeconds(150), false);

        insertAttack(SELF_USER_1, SELF_FACTION, OPPONENT_USER_1, OPPONENT_FACTION, BASE.plusSeconds(10));
        insertAttack(SELF_USER_1, SELF_FACTION, OPPONENT_USER_1, OPPONENT_FACTION, BASE.plusSeconds(20));
        insertAttack(OPPONENT_USER_1, OPPONENT_FACTION, SELF_USER_1, SELF_FACTION, BASE.plusSeconds(30));
        insertThirdPartyAttack(BASE.plusSeconds(40));
        for (int i = 0; i < 5; i++) {
            insertAttack(SELF_USER_3, SELF_FACTION, OPPONENT_USER_4, OPPONENT_FACTION,
                    BASE.plusMinutes(10).plusSeconds(10L + i));
        }
        insertAttack(OPPONENT_USER_4, OPPONENT_FACTION, SELF_USER_3, SELF_FACTION, BASE.plusMinutes(10).plusSeconds(20));

        List<RwStatWindowVO> catalog = windowDao.queryWindowCatalog(RW_ID, SELF_FACTION, OPPONENT_FACTION);

        assertEquals(3, catalog.size());
        RwStatWindowVO windowA = catalog.stream().filter(item -> "A".equals(item.getWindowCode())).findFirst().orElseThrow();
        RwStatWindowVO windowB = catalog.stream().filter(item -> "B".equals(item.getWindowCode())).findFirst().orElseThrow();
        assertEquals(2, windowA.getSelfAttackCount());
        assertEquals(1, windowA.getOpponentAttackCount());
        assertEquals(5, windowB.getSelfAttackCount());
        assertEquals(1, windowB.getOpponentAttackCount());

        TornFactionRwStatWindowDO latest = windowDao.queryLatestConfirmedWindow(RW_ID, SELF_FACTION, OPPONENT_FACTION);
        assertNotNull(latest);
        assertEquals("B", latest.getWindowCode());
    }

    @Test
    @DisplayName("窗口用户出手聚合按攻击方阵营分组且频率为次数乘以60除以窗口秒数")
    void queryUserAttackStats_aggregatesByFactionAndCalculatesRate() {
        insertWindow("B", BASE.plusMinutes(10), BASE.plusMinutes(10).plusSeconds(150), true);
        for (int i = 0; i < 5; i++) {
            insertAttack(SELF_USER_3, SELF_FACTION, OPPONENT_USER_4, OPPONENT_FACTION,
                    BASE.plusMinutes(10).plusSeconds(10L + i));
        }
        insertAttack(OPPONENT_USER_4, OPPONENT_FACTION, SELF_USER_3, SELF_FACTION,
                BASE.plusMinutes(10).plusSeconds(20));
        insertThirdPartyAttack(BASE.plusMinutes(10).plusSeconds(30));

        List<RwUserAttackStatVO> users = windowDao.queryUserAttackStats(
                BASE.plusMinutes(10), BASE.plusMinutes(10).plusSeconds(150),
                SELF_FACTION, OPPONENT_FACTION);

        assertEquals(2, users.size());
        RwUserAttackStatVO self = users.stream()
                .filter(user -> user.getAttackFactionId() == SELF_FACTION)
                .findFirst().orElseThrow();
        RwUserAttackStatVO opponent = users.stream()
                .filter(user -> user.getAttackFactionId() == OPPONENT_FACTION)
                .findFirst().orElseThrow();
        assertEquals(SELF_USER_3, self.getUserId());
        assertEquals(5, self.getAttackCount());
        assertEquals(0, new BigDecimal("2.00").compareTo(self.getAttackRatePerMinute()));
        assertEquals(OPPONENT_USER_4, opponent.getUserId());
        assertEquals(1, opponent.getAttackCount());
        assertTrue(users.stream().noneMatch(user -> user.getAttackFactionId() == THIRD_FACTION),
                "第三方帮派攻击不得混入用户聚合");
    }

    private void insertWindow(String code, LocalDateTime start, LocalDateTime end, boolean confirmed) {
        TornFactionRwStatWindowDO window = new TornFactionRwStatWindowDO();
        window.setId(IdWorker.getId());
        window.setRwId(RW_ID);
        window.setWindowCode(code);
        window.setStartTime(start);
        window.setEndTime(end);
        window.setConfirmed(confirmed);
        assertTrue(windowDao.save(window), "窗口测试数据必须插入成功");
    }

    private void insertAttack(long attackUserId, long attackFactionId, long defendUserId, long defendFactionId,
                              LocalDateTime startTime) {
        TornFactionAttackDO attack = new TornFactionAttackDO();
        attack.setId(IdWorker.getId());
        attack.setAttackUserId(attackUserId);
        attack.setAttackUserNickname("测试攻方" + attackUserId);
        attack.setAttackFactionId(attackFactionId);
        attack.setAttackFactionName("测试帮派" + attackFactionId);
        attack.setDefendUserId(defendUserId);
        attack.setDefendUserNickname("测试守方" + defendUserId);
        attack.setDefendFactionId(defendFactionId);
        attack.setDefendFactionName("测试帮派" + defendFactionId);
        attack.setDefendUserOnlineStatus(TornConstants.USER_STATUS_ONLINE);
        attack.setAttackStartTime(startTime);
        attack.setAttackEndTime(startTime.plusSeconds(5));
        attack.setAttackResult("Success");
        attack.setAttackLogId("rw-stat-window-test-" + IdWorker.getIdStr());
        attack.setAttackerElo(0);
        attack.setDefenderElo(0);
        attack.setRespectGain(BigDecimal.ZERO);
        attack.setRespectLoss(BigDecimal.ZERO);
        attack.setChain(0);
        attack.setIsInterrupted(false);
        attack.setIsStealth(false);
        attack.setIsRaid(false);
        attack.setIsRankedWar(true);
        attack.setModifierFairFight(BigDecimal.ONE);
        attack.setModifierWar(BigDecimal.ONE);
        attack.setModifierRetaliation(BigDecimal.ZERO);
        attack.setModifierGroup(BigDecimal.ZERO);
        attack.setModifierOversea(BigDecimal.ZERO);
        attack.setModifierChain(BigDecimal.ZERO);
        attack.setModifierWarlord(BigDecimal.ZERO);
        assertTrue(attackDao.save(attack), "攻击测试数据必须插入成功");
    }

    private void insertThirdPartyAttack(LocalDateTime startTime) {
        TornFactionAttackDO attack = new TornFactionAttackDO();
        attack.setId(IdWorker.getId());
        attack.setAttackUserId(THIRD_USER);
        attack.setAttackUserNickname("第三方攻方");
        attack.setAttackFactionId(THIRD_FACTION);
        attack.setAttackFactionName("第三方帮派");
        attack.setDefendUserId(SELF_USER_1);
        attack.setDefendUserNickname("己方守方");
        attack.setDefendFactionId(SELF_FACTION);
        attack.setDefendFactionName("己方帮派");
        attack.setDefendUserOnlineStatus(TornConstants.USER_STATUS_ONLINE);
        attack.setAttackStartTime(startTime);
        attack.setAttackEndTime(startTime.plusSeconds(5));
        attack.setAttackResult("Success");
        attack.setAttackLogId("rw-stat-window-third-party-" + IdWorker.getIdStr());
        attack.setAttackerElo(0);
        attack.setDefenderElo(0);
        attack.setRespectGain(BigDecimal.ZERO);
        attack.setRespectLoss(BigDecimal.ZERO);
        attack.setChain(0);
        attack.setIsInterrupted(false);
        attack.setIsStealth(false);
        attack.setIsRaid(false);
        attack.setIsRankedWar(true);
        attack.setModifierFairFight(BigDecimal.ONE);
        attack.setModifierWar(BigDecimal.ONE);
        attack.setModifierRetaliation(BigDecimal.ZERO);
        attack.setModifierGroup(BigDecimal.ZERO);
        attack.setModifierOversea(BigDecimal.ZERO);
        attack.setModifierChain(BigDecimal.ZERO);
        attack.setModifierWarlord(BigDecimal.ZERO);
        assertTrue(attackDao.save(attack), "第三方攻击测试数据必须插入成功");
    }
}
