package pn.torn.goldeneye.torn.manager.faction.attack;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgReqParam;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwUserStatusDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwUserStatusDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberVO;
import pn.torn.goldeneye.torn.model.user.TornUserLastActionVO;
import pn.torn.goldeneye.torn.model.user.TornUserStatusVO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RW敌对成员旅行快照变更警告测试
 * <p>
 * 验证旅行状态判定使用完整旅行快照四字段比较: Torn成员接口中返程顶层state仍为
 * Traveling, 仅status.description由"Traveling to ..."变为"Returning from ...",
 * 旧实现只比较state导致Traveling→Returning不更新快照、消息持续显示"起飞"。
 * 修复后该变化必须持久化新快照且群消息文本显示"返回"; 同一完整快照第二次调用
 * 不产生状态更新与Bot发送。Bot请求体中从TextQqMsg具体消息data抽取文本断言。
 *
 * @author Bai
 * @version 1.3.5
 * @since 2026.08.20
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RW敌对成员旅行快照变更警告测试")
class TornRwWarningManagerTest {

    private static final long FACTION_ID = 100L;
    private static final long RW_ID = 1L;
    private static final long OPPONENT_USER_ID = 200001L;

    @Mock
    private Bot bot;

    @Mock
    private TornUserManager userManager;

    @Mock
    private TornSettingFactionManager factionManager;

    @Mock
    private TornFactionRwUserStatusDAO userStatusDao;

    @InjectMocks
    private TornRwWarningManager rwWarningManager;

    @Test
    @DisplayName("去程转返程_顶层state不变时快照仍更新, 群消息显示返回")
    void sendWarning_travelingToReturning_updatesSnapshotAndShowsReturning() {
        TornSettingFactionDO faction = new TornSettingFactionDO();
        faction.setId(FACTION_ID);
        faction.setGroupId(888888L);
        faction.setWarCommanderIds("10000");
        when(factionManager.getIdMap()).thenReturn(Map.of(FACTION_ID, faction));
        when(bot.sendRequest(any(), eq(String.class))).thenReturn(ResponseEntity.ok("ok"));
        // 已有敌对成员去程快照: state=Traveling, travelType=Traveling, 目的地Japan, 机型Airstrip
        TornFactionRwUserStatusDO existing = new TornFactionRwUserStatusDO(FACTION_ID, RW_ID,
                opponentMember("Traveling to Japan"));
        existing.setId(501L);
        stubStatusQuery(List.of(existing));
        // 敌对成员不属于我方帮派, 走fillOpponentData路径
        when(userManager.getUserById(OPPONENT_USER_ID)).thenReturn(opponentUser(999L));

        rwWarningManager.sendWarning(rw(), LocalDateTime.now(),
                List.of(opponentMember("Returning from Japan")));

        ArgumentCaptor<List<TornFactionRwUserStatusDO>> updateCaptor = ArgumentCaptor.captor();
        verify(userStatusDao).updateBatchById(updateCaptor.capture());
        List<TornFactionRwUserStatusDO> updatedList = updateCaptor.getValue();
        assertEquals(1, updatedList.size(), "旅行方向变化必须持久化新快照");
        assertEquals(501L, updatedList.getFirst().getId(), "更新必须复用已有快照主键");
        assertEquals("Returning", updatedList.getFirst().getTravelType(),
                "description为Returning from ...时必须解析为Returning");

        String msgText = extractBotMsgText();
        assertTrue(msgText.contains("返回"), "群消息文本必须显示'返回'而非'起飞': " + msgText);
        verify(userStatusDao, never()).saveBatch(any());
    }

    @Test
    @DisplayName("同一返程快照_第二次调用无状态更新且无Bot发送")
    void sendWarning_sameReturningSnapshot_noSecondUpdateOrNotice() {
        TornFactionRwUserStatusDO existing = new TornFactionRwUserStatusDO(FACTION_ID, RW_ID,
                opponentMember("Returning from Japan"));
        existing.setId(501L);
        stubStatusQuery(List.of(existing));
        when(userManager.getUserById(OPPONENT_USER_ID)).thenReturn(opponentUser(999L));

        rwWarningManager.sendWarning(rw(), LocalDateTime.now(),
                List.of(opponentMember("Returning from Japan")));

        verify(userStatusDao, never()).updateBatchById(any());
        verify(userStatusDao, never()).saveBatch(any());
        verify(bot, never()).sendRequest(any(), any());
    }

    /**
     * 桩化RW用户状态查询链, 返回指定已有快照列表
     *
     * @param statusList 已有快照列表
     */
    @SuppressWarnings("unchecked")
    private void stubStatusQuery(List<TornFactionRwUserStatusDO> statusList) {
        LambdaQueryChainWrapper<TornFactionRwUserStatusDO> queryWrapper =
                mock(LambdaQueryChainWrapper.class);
        doReturn(queryWrapper).when(queryWrapper).eq(any(SFunction.class), any());
        doReturn(statusList).when(queryWrapper).list();
        doReturn(queryWrapper).when(userStatusDao).lambdaQuery();
    }

    /**
     * 构造RW对象
     *
     * @return RW对象
     */
    private TornFactionRwDO rw() {
        TornFactionRwDO rwDO = new TornFactionRwDO();
        rwDO.setId(RW_ID);
        rwDO.setFactionId(FACTION_ID);
        return rwDO;
    }

    /**
     * 构造敌对成员响应, 顶层state固定为Traveling
     *
     * @param description 状态描述, 如"Traveling to Japan"/"Returning from Japan"
     * @return 帮派成员响应对象
     */
    private TornFactionMemberVO opponentMember(String description) {
        TornFactionMemberVO member = new TornFactionMemberVO();
        member.setId(OPPONENT_USER_ID);
        member.setName("rw测试对手");
        TornUserStatusVO status = new TornUserStatusVO();
        status.setState("Traveling");
        status.setDescription(description);
        status.setPlaneImageType("light_aircraft");
        member.setStatus(status);
        TornUserLastActionVO lastAction = new TornUserLastActionVO();
        lastAction.setTimestamp(System.currentTimeMillis() / 1000);
        member.setLastAction(lastAction);
        return member;
    }

    /**
     * 构造成员对应的用户信息
     *
     * @param factionId 用户所属帮派ID, 与RW我方帮派不同即为敌对方
     * @return 用户对象
     */
    private TornUserDO opponentUser(long factionId) {
        TornUserDO user = new TornUserDO();
        user.setId(OPPONENT_USER_ID);
        user.setNickname("rw测试对手");
        user.setFactionId(factionId);
        user.setQqId(0L);
        return user;
    }

    /**
     * 从Bot群消息请求体中抽取全部文本消息内容
     *
     * @return 拼接后的文本内容
     */
    private String extractBotMsgText() {
        ArgumentCaptor<BotHttpReqParam> paramCaptor = ArgumentCaptor.captor();
        verify(bot).sendRequest(paramCaptor.capture(), eq(String.class));
        GroupMsgReqParam body = (GroupMsgReqParam) paramCaptor.getValue().body();
        StringBuilder textBuilder = new StringBuilder();
        for (QqMsgParam<?> msg : body.getMessage()) {
            if (msg instanceof TextQqMsg textMsg) {
                textBuilder.append(textMsg.getData().text());
            }
        }
        return textBuilder.toString();
    }
}
