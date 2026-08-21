package pn.torn.goldeneye.torn.service.faction.attack;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionAttackDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionAttackDO;
import pn.torn.goldeneye.torn.model.faction.attack.TornFactionAttackModifierVO;
import pn.torn.goldeneye.torn.model.faction.attack.TornFactionAttackRespVO;
import pn.torn.goldeneye.torn.model.faction.attack.TornFactionAttackUserVO;
import pn.torn.goldeneye.torn.model.faction.attack.TornFactionAttackVO;
import pn.torn.goldeneye.torn.service.data.TornAttackLogService;
import pn.torn.goldeneye.torn.service.user.TornUserStateService;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * 帮派攻击记录解析测试。
 *
 * @author Bai
 * @version 1.3.8
 * @since 2026.08.21
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("帮派攻击记录解析测试")
class TornFactionAttackServiceTest {

    private static final long DEFENDER_ID = 1513571L;

    @Mock
    private TornApi tornApi;

    @Mock
    private TornAttackLogService attackLogService;

    @Mock
    private TornUserStateService userStateService;

    @Mock
    private TornFactionAttackDAO attackDao;

    @Test
    @DisplayName("已有攻击记录仍收集非空attackLogId")
    void parseAttackList_existingAttack_collectsLogIdWithoutReSave() {
        long attackId = 460001L;
        stubExistingIds(List.of(buildExistingAttack(attackId)));
        TornFactionAttackRespVO resp = buildResp(buildAttack(attackId, "3762529d"));

        Set<String> logIdSet = new HashSet<>();
        List<TornFactionAttackDO> attackList = buildService().parseAttackList(
                LocalDateTime.now(), resp, Map.of(), logIdSet, new HashMap<>(), Map.of());

        assertTrue(attackList.isEmpty(), "已存在攻击不得重复生成DO落库");
        assertEquals(Set.of("3762529d"), logIdSet, "已存在攻击的非空日志Code必须收集, 保证重叠重试可重抓");
    }

    @Test
    @DisplayName("空白attackLogId不收集")
    void parseAttackList_blankLogId_notCollected() {
        stubExistingIds(List.of());
        TornFactionAttackRespVO resp = buildResp(
                buildAttack(460002L, null), buildAttack(460003L, "   "));

        Set<String> logIdSet = new HashSet<>();
        List<TornFactionAttackDO> attackList = buildService().parseAttackList(
                LocalDateTime.now(), resp, Map.of(), logIdSet, new HashMap<>(), Map.of());

        assertTrue(logIdSet.isEmpty(), "null或空白Code不得进入logIdSet");
        assertEquals(2, attackList.size(), "新攻击仍正常解析为DO");
    }

    private TornFactionAttackService buildService() {
        return new TornFactionAttackService(tornApi, attackLogService, userStateService, attackDao);
    }

    private void stubExistingIds(List<TornFactionAttackDO> existingList) {
        LambdaQueryChainWrapper<TornFactionAttackDO> wrapper = mock(LambdaQueryChainWrapper.class);
        doReturn(wrapper).when(attackDao).lambdaQuery();
        doReturn(wrapper).when(wrapper).in(any(), anyCollection());
        doReturn(existingList).when(wrapper).list();
    }

    private TornFactionAttackDO buildExistingAttack(long attackId) {
        TornFactionAttackDO existing = new TornFactionAttackDO();
        existing.setId(attackId);
        return existing;
    }

    private TornFactionAttackRespVO buildResp(TornFactionAttackVO... attacks) {
        TornFactionAttackRespVO resp = new TornFactionAttackRespVO();
        resp.setAttacks(List.of(attacks));
        return resp;
    }

    private TornFactionAttackVO buildAttack(long id, String code) {
        TornFactionAttackUserVO defender = new TornFactionAttackUserVO();
        defender.setId(DEFENDER_ID);
        defender.setName("rwDefender");

        TornFactionAttackVO attack = new TornFactionAttackVO();
        attack.setId(id);
        attack.setCode(code);
        attack.setDefender(defender);
        attack.setModifiers(new TornFactionAttackModifierVO());
        return attack;
    }
}
