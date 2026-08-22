package pn.torn.goldeneye.napcat.strategy.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.receive.parser.QqCommandMessage;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.torn.model.activity.PersonalActivityHeatmapVO;
import pn.torn.goldeneye.torn.service.activity.ActivityHeatmapService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 活跃度热力图指令测试。
 *
 * <p>覆盖“用户”模式 at 目标到绑定用户 Torn userId 的转换、“帮派”模式对 at 目标的拒绝，
 * 以及数字目标、at 未绑定、at 与数字混用和非法标记的参数边界。</p>
 *
 * @author Bai
 * @version 1.4.0
 * @since 2026.07.21
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("活跃度热力图指令测试")
class ActivityHeatmapStrategyImplTest {

    private static final long GROUP_ID = 111L;
    private static final long AT_TARGET_QQ = 12345L;
    private static final long BOUND_TORN_USER_ID = 54321L;

    @Mock
    private TornUserManager userManager;
    @Mock
    private ActivityHeatmapService heatmapService;

    private ActivityHeatmapStrategyImpl strategy;

    @BeforeEach
    void setUp() {
        strategy = new ActivityHeatmapStrategyImpl(heatmapService);
        ReflectionTestUtils.setField(strategy, "userManager", userManager);
    }

    @Test
    @DisplayName("合法类型和正数ID应通过校验")
    void shouldAcceptValidTypeAndPositiveId() {
        assertTrue(ActivityHeatmapStrategyImpl.isValidQuery("帮派", "20465"));
        assertTrue(ActivityHeatmapStrategyImpl.isValidQuery("用户", "12345"));
    }

    @Test
    @DisplayName("无效类型及非正数ID应拒绝")
    void shouldRejectInvalidTypeAndNonPositiveId() {
        assertFalse(ActivityHeatmapStrategyImpl.isValidQuery("其他", "20465"));
        assertFalse(ActivityHeatmapStrategyImpl.isValidQuery("帮派", "0"));
        assertFalse(ActivityHeatmapStrategyImpl.isValidQuery("用户", "-1"));
        assertFalse(ActivityHeatmapStrategyImpl.isValidQuery(
                "用户", "999999999999999999999999999"));
    }

    @Test
    @DisplayName("用户模式 at 目标按 QQ 解析绑定用户并以其 Torn userId 查询个人热力图")
    void handle_userModeAtTarget_queriesByBoundTornUserId() {
        TornUserDO boundUser = new TornUserDO();
        boundUser.setId(BOUND_TORN_USER_ID);
        when(userManager.getUserByQq(AT_TARGET_QQ)).thenReturn(boundUser);
        PersonalActivityHeatmapVO heatmap = insufficientPersonalHeatmap();
        when(heatmapService.queryPersonalHeatmap(BOUND_TORN_USER_ID, 28)).thenReturn(heatmap);

        List<? extends QqMsgParam<?>> result =
                strategy.handle(GROUP_ID, sender(), "用户#" + QqCommandMessage.buildAtMarker(AT_TARGET_QQ));

        assertEquals(heatmap.getInsufficientMessage(), replyText(result));
        verify(userManager).getUserByQq(AT_TARGET_QQ);
        verify(heatmapService).queryPersonalHeatmap(BOUND_TORN_USER_ID, 28);
    }

    @Test
    @DisplayName("用户模式普通数字仍按 Torn userId 查询且不走 QQ 绑定解析")
    void handle_userModeNumericTarget_keepsTornUserIdQuery() {
        when(heatmapService.queryPersonalHeatmap(BOUND_TORN_USER_ID, 28))
                .thenReturn(insufficientPersonalHeatmap());

        List<? extends QqMsgParam<?>> result = strategy.handle(GROUP_ID, sender(), "用户#" + BOUND_TORN_USER_ID);

        assertFalse(result.isEmpty());
        verify(userManager, never()).getUserByQq(anyLong());
        verify(heatmapService).queryPersonalHeatmap(BOUND_TORN_USER_ID, 28);
    }

    @Test
    @DisplayName("帮派模式 at 目标返回参数错误且不调用任何热力图服务")
    void handle_factionModeAtTarget_rejectedWithoutHeatmapQuery() {
        List<? extends QqMsgParam<?>> result =
                strategy.handle(GROUP_ID, sender(), "帮派#" + QqCommandMessage.buildAtMarker(AT_TARGET_QQ));

        assertTrue(replyText(result).contains("查询格式举例"), "帮派模式 at 应返回格式介绍参数错误");
        verify(heatmapService, never()).queryFactionHeatmap(anyLong(), anyInt());
        verify(heatmapService, never()).queryPersonalHeatmap(anyLong(), anyInt());
        verify(userManager, never()).getUserByQq(anyLong());
    }

    @Test
    @DisplayName("at 目标未绑定用户时返回既有业务提示且不调用个人热力图服务")
    void handle_userModeAtNotBound_throwsBusinessTipWithoutHeatmapQuery() {
        when(userManager.getUserByQq(AT_TARGET_QQ)).thenReturn(null);
        QqRecMsgSender sender = sender();
        String msg = "用户#" + QqCommandMessage.buildAtMarker(AT_TARGET_QQ);

        BizException exception = assertThrows(BizException.class,
                () -> strategy.handle(GROUP_ID, sender, msg));

        assertEquals("金蝶不认识你哦", exception.getMsg());
        verify(heatmapService, never()).queryPersonalHeatmap(anyLong(), anyInt());
    }

    @Test
    @DisplayName("at 与数字混用时返回参数有误且不调用任何热力图服务")
    void handle_userModeAtAndNumericMixed_rejectedWithoutHeatmapQuery() {
        String mixedTarget = BOUND_TORN_USER_ID + QqCommandMessage.buildAtMarker(AT_TARGET_QQ);
        QqRecMsgSender sender = sender();

        BizException exception = assertThrows(BizException.class,
                () -> strategy.handle(GROUP_ID, sender, "用户#" + mixedTarget));

        assertEquals("参数有误", exception.getMsg());
        verify(heatmapService, never()).queryFactionHeatmap(anyLong(), anyInt());
        verify(heatmapService, never()).queryPersonalHeatmap(anyLong(), anyInt());
    }

    @Test
    @DisplayName("非法 at 标记返回参数有误且不调用任何热力图服务")
    void handle_invalidAtMarker_rejectedWithoutHeatmapQuery() {
        QqRecMsgSender sender = sender();
        String msg = "用户#" + QqCommandMessage.INVALID_AT_MARKER;

        BizException exception = assertThrows(BizException.class,
                () -> strategy.handle(GROUP_ID, sender, msg));

        assertEquals("参数有误", exception.getMsg());
        verify(heatmapService, never()).queryFactionHeatmap(anyLong(), anyInt());
        verify(heatmapService, never()).queryPersonalHeatmap(anyLong(), anyInt());
    }

    private QqRecMsgSender sender() {
        QqRecMsgSender sender = new QqRecMsgSender();
        sender.setUserId(999L);
        return sender;
    }

    private PersonalActivityHeatmapVO insufficientPersonalHeatmap() {
        PersonalActivityHeatmapVO heatmap = new PersonalActivityHeatmapVO();
        heatmap.setDataSufficient(false);
        heatmap.setInsufficientMessage("近期活跃数据不足");
        return heatmap;
    }

    private String replyText(List<? extends QqMsgParam<?>> result) {
        return ((TextQqMsg) result.getFirst()).getData().text();
    }
}
