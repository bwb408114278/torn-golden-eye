package pn.torn.goldeneye.napcat.strategy.faction.crime;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.receive.parser.QqCommandMessage;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.faction.crime.msg.TornFactionOcMsgTableManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionOcManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcSlotManager;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * OC 成功率策略 at 用户目标调用链测试。
 *
 * @author Bai
 * @version 1.4.0
 * @since 2026.08.21
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OC成功率策略 at 用户目标测试")
class OcRateQueryStrategyImplTest {

    @Mock
    private TornFactionOcMsgTableManager msgTableManager;
    @Mock
    private TornFactionOcUserDAO ocUserDao;
    @Mock
    private TornSettingOcManager settingOcManager;
    @Mock
    private TornSettingOcSlotManager settingOcSlotManager;
    @Mock
    private TornSettingFactionOcManager settingFactionOcManager;
    @Mock
    private TornUserManager userManager;

    private OcRateQueryStrategyImpl strategy;

    @BeforeEach
    void setUp() {
        strategy = new OcRateQueryStrategyImpl(msgTableManager, ocUserDao, settingOcManager,
                settingOcSlotManager, settingFactionOcManager);
        ReflectionTestUtils.setField(strategy, "userManager", userManager);
    }

    @Test
    @DisplayName("at 输入按 QQ 查询目标用户")
    void handle_atTarget_callsGetUserByQq() {
        QqRecMsgSender sender = sender();
        TornUserDO user = user(1L);
        when(userManager.getUserByQq(12345L)).thenReturn(user);
        stubOcUserQueryEmpty();

        List<? extends QqMsgParam<?>> result = strategy.handle(0L, sender,
                QqCommandMessage.buildAtMarker(12345L));

        assertEquals("暂未查询到记录的OC成功率", ((TextQqMsg) result.getFirst()).getData().text());
        verify(userManager).getUserByQq(12345L);
    }

    @Test
    @DisplayName("数字 userId 输入仍按 Torn userId 查询")
    void handle_numericTarget_callsGetUserById() {
        QqRecMsgSender sender = sender();
        TornUserDO user = user(12345L);
        when(userManager.getUserById(12345L)).thenReturn(user);
        stubOcUserQueryEmpty();

        List<? extends QqMsgParam<?>> result = strategy.handle(0L, sender, "12345");

        assertEquals("暂未查询到记录的OC成功率", ((TextQqMsg) result.getFirst()).getData().text());
        verify(userManager).getUserById(12345L);
    }

    private void stubOcUserQueryEmpty() {
        LambdaQueryChainWrapper<TornFactionOcUserDO> query = mock(LambdaQueryChainWrapper.class);
        when(ocUserDao.lambdaQuery()).thenReturn(query);
        when(query.eq(any(), any())).thenReturn(query);
        when(query.orderByDesc(any(SFunction.class))).thenReturn(query);
        when(query.orderByAsc(any(SFunction.class))).thenReturn(query);
        when(query.list()).thenReturn(List.of());
    }

    private QqRecMsgSender sender() {
        QqRecMsgSender sender = new QqRecMsgSender();
        sender.setUserId(999L);
        return sender;
    }

    private TornUserDO user(long id) {
        TornUserDO user = new TornUserDO();
        user.setId(id);
        return user;
    }
}
