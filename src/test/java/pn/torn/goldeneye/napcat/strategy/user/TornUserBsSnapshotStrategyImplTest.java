package pn.torn.goldeneye.napcat.strategy.user;

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
import pn.torn.goldeneye.repository.dao.setting.TornApiKeyDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserBsSnapshotDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 战力增长策略 at 用户目标调用链测试。
 *
 * @author Bai
 * @version 1.4.0
 * @since 2026.08.21
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("战力增长策略 at 用户目标测试")
class TornUserBsSnapshotStrategyImplTest {

    @Mock
    private TornUserBsSnapshotDAO bsSnapshotDao;
    @Mock
    private TornApiKeyDAO keyDao;
    @Mock
    private TornUserManager userManager;

    private TornUserBsSnapshotStrategyImpl strategy;

    @BeforeEach
    void setUp() {
        strategy = new TornUserBsSnapshotStrategyImpl(bsSnapshotDao, keyDao);
        ReflectionTestUtils.setField(strategy, "userManager", userManager);
    }

    @Test
    @DisplayName("at 输入按 QQ 查询目标用户")
    void handle_atTarget_callsGetUserByQq() {
        QqRecMsgSender sender = sender();
        TornUserDO user = user(1L);
        when(userManager.getUserByQq(12345L)).thenReturn(user);
        stubKeyQueryReturnsNull();

        List<? extends QqMsgParam<?>> result = strategy.handle(0L, sender,
                QqCommandMessage.buildAtMarker(12345L));

        assertEquals("这个人还没有绑定Key哦", ((TextQqMsg) result.getFirst()).getData().text());
        verify(userManager).getUserByQq(12345L);
    }

    @Test
    @DisplayName("数字 userId 输入仍按 Torn userId 查询")
    void handle_numericTarget_callsGetUserById() {
        QqRecMsgSender sender = sender();
        TornUserDO user = user(12345L);
        when(userManager.getUserById(12345L)).thenReturn(user);
        stubKeyQueryReturnsNull();

        List<? extends QqMsgParam<?>> result = strategy.handle(0L, sender, "12345");

        assertEquals("这个人还没有绑定Key哦", ((TextQqMsg) result.getFirst()).getData().text());
        verify(userManager).getUserById(12345L);
    }

    private void stubKeyQueryReturnsNull() {
        LambdaQueryChainWrapper<TornApiKeyDO> keyQuery = mock(LambdaQueryChainWrapper.class);
        when(keyDao.lambdaQuery()).thenReturn(keyQuery);
        when(keyQuery.eq(any(), any())).thenReturn(keyQuery);
        when(keyQuery.one()).thenReturn(null);
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
