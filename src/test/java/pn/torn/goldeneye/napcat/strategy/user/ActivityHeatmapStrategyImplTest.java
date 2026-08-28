package pn.torn.goldeneye.napcat.strategy.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import pn.torn.goldeneye.torn.model.activity.ActivityQueryRange;
import pn.torn.goldeneye.torn.model.activity.ActivityQueryRangeModeEnum;
import pn.torn.goldeneye.torn.model.activity.FactionActivityHeatmapVO;
import pn.torn.goldeneye.torn.model.activity.PersonalActivityHeatmapVO;
import pn.torn.goldeneye.torn.service.activity.ActivityHeatmapService;
import pn.torn.goldeneye.torn.service.activity.TornActivityCollectService;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 活跃度热力图指令测试。
 *
 * <p>覆盖“用户”模式 at 目标到绑定用户 Torn userId 的转换、“帮派”模式对 at 目标的拒绝、
 * 数字目标、at 未绑定、at 与数字混用、非法标记的参数边界，以及从/截至日期参数的
 * 范围传递与非法日期拒绝。</p>
 *
 * @author Bai
 * @version 1.5.0
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
    @DisplayName("无日期参数的指令以默认最近28天范围查询")
    void handle_noDateParam_queriesWithDefaultRange() {
        when(heatmapService.queryPersonalHeatmap(eq(BOUND_TORN_USER_ID), any(ActivityQueryRange.class)))
                .thenReturn(noDataPersonalHeatmap());

        strategy.handle(GROUP_ID, sender(), "用户#" + BOUND_TORN_USER_ID);

        ArgumentCaptor<ActivityQueryRange> captor = ArgumentCaptor.forClass(ActivityQueryRange.class);
        verify(heatmapService).queryPersonalHeatmap(eq(BOUND_TORN_USER_ID), captor.capture());
        assertEquals(ActivityQueryRangeModeEnum.DEFAULT, captor.getValue().mode());
        assertEquals(LocalDate.now(TornActivityCollectService.HEATMAP_ZONE), captor.getValue().endDate());
        assertEquals(28, captor.getValue().totalDays());
    }

    @Test
    @DisplayName("从#日期 参数应以 [startDate, 今天] 范围传递给查询服务")
    void handle_fromDateParam_passesAnchoredRange() {
        when(heatmapService.queryPersonalHeatmap(eq(BOUND_TORN_USER_ID), any(ActivityQueryRange.class)))
                .thenReturn(noDataPersonalHeatmap());

        strategy.handle(GROUP_ID, sender(), "用户#" + BOUND_TORN_USER_ID + "#从#2026-08-01");

        ArgumentCaptor<ActivityQueryRange> captor = ArgumentCaptor.forClass(ActivityQueryRange.class);
        verify(heatmapService).queryPersonalHeatmap(eq(BOUND_TORN_USER_ID), captor.capture());
        assertEquals(ActivityQueryRangeModeEnum.FROM, captor.getValue().mode());
        assertEquals(LocalDate.of(2026, 8, 1), captor.getValue().startDate());
        assertEquals(LocalDate.now(TornActivityCollectService.HEATMAP_ZONE), captor.getValue().endDate());
    }

    @Test
    @DisplayName("截至#日期 参数应以 [endDate-27, endDate] 范围传递给查询服务")
    void handle_untilDateParam_passesAnchoredRange() {
        when(heatmapService.queryFactionHeatmap(eq(20465L), any(ActivityQueryRange.class)))
                .thenReturn(noDataFactionHeatmap());

        strategy.handle(GROUP_ID, sender(), "帮派#20465#截至#2026-08-01");

        ArgumentCaptor<ActivityQueryRange> captor = ArgumentCaptor.forClass(ActivityQueryRange.class);
        verify(heatmapService).queryFactionHeatmap(eq(20465L), captor.capture());
        assertEquals(ActivityQueryRangeModeEnum.UNTIL, captor.getValue().mode());
        assertEquals(LocalDate.of(2026, 8, 1), captor.getValue().endDate());
        assertEquals(LocalDate.of(2026, 7, 5), captor.getValue().startDate());
    }

    @Test
    @DisplayName("非法日期格式/未来日期/错误关键字的日期参数应返回格式说明")
    void handle_invalidDateParams_returnsFormatIntro() {
        assertFormatIntro("用户#" + BOUND_TORN_USER_ID + "#从#2026/08/01");
        assertFormatIntro("用户#" + BOUND_TORN_USER_ID + "#从#2999-01-01");
        assertFormatIntro("帮派#20465#自从#2026-08-01");
        assertFormatIntro("帮派#20465#从#2026-08-01#截至#2026-08-02");
        assertFormatIntro("帮派#20465#从");

        verify(heatmapService, never()).queryPersonalHeatmap(anyLong(), any(ActivityQueryRange.class));
        verify(heatmapService, never()).queryFactionHeatmap(anyLong(), any(ActivityQueryRange.class));
    }

    @Test
    @DisplayName("用户模式 at 目标按 QQ 解析绑定用户并以其 Torn userId 查询个人热力图")
    void handle_userModeAtTarget_queriesByBoundTornUserId() {
        TornUserDO boundUser = new TornUserDO();
        boundUser.setId(BOUND_TORN_USER_ID);
        when(userManager.getUserByQq(AT_TARGET_QQ)).thenReturn(boundUser);
        when(heatmapService.queryPersonalHeatmap(eq(BOUND_TORN_USER_ID), any(ActivityQueryRange.class)))
                .thenReturn(noDataPersonalHeatmap());

        List<? extends QqMsgParam<?>> result = strategy.handle(GROUP_ID, sender(),
                "用户#" + QqCommandMessage.buildAtMarker(AT_TARGET_QQ));

        assertEquals(ActivityHeatmapService.NO_DATA_MESSAGE, replyText(result));
        verify(userManager).getUserByQq(AT_TARGET_QQ);
        verify(heatmapService).queryPersonalHeatmap(eq(BOUND_TORN_USER_ID), any(ActivityQueryRange.class));
    }

    @Test
    @DisplayName("用户模式 at 目标携带从#日期 参数时范围仍正确传递")
    void handle_userModeAtTargetWithFromDate_passesRange() {
        TornUserDO boundUser = new TornUserDO();
        boundUser.setId(BOUND_TORN_USER_ID);
        when(userManager.getUserByQq(AT_TARGET_QQ)).thenReturn(boundUser);
        when(heatmapService.queryPersonalHeatmap(eq(BOUND_TORN_USER_ID), any(ActivityQueryRange.class)))
                .thenReturn(noDataPersonalHeatmap());

        strategy.handle(GROUP_ID, sender(),
                "用户#" + QqCommandMessage.buildAtMarker(AT_TARGET_QQ) + "#从#2026-08-01");

        ArgumentCaptor<ActivityQueryRange> captor = ArgumentCaptor.forClass(ActivityQueryRange.class);
        verify(heatmapService).queryPersonalHeatmap(eq(BOUND_TORN_USER_ID), captor.capture());
        assertEquals(ActivityQueryRangeModeEnum.FROM, captor.getValue().mode());
    }

    @Test
    @DisplayName("帮派模式 at 目标返回参数错误且不调用任何热力图服务")
    void handle_factionModeAtTarget_rejectedWithoutHeatmapQuery() {
        List<? extends QqMsgParam<?>> result = strategy.handle(GROUP_ID, sender(),
                "帮派#" + QqCommandMessage.buildAtMarker(AT_TARGET_QQ));

        assertTrue(replyText(result).contains("查询格式举例"), "帮派模式 at 应返回格式介绍参数错误");
        verify(heatmapService, never()).queryFactionHeatmap(anyLong(), any(ActivityQueryRange.class));
        verify(heatmapService, never()).queryPersonalHeatmap(anyLong(), any(ActivityQueryRange.class));
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
        verify(heatmapService, never()).queryPersonalHeatmap(anyLong(), any(ActivityQueryRange.class));
    }

    @Test
    @DisplayName("at 与数字混用时返回参数有误且不调用任何热力图服务")
    void handle_userModeAtAndNumericMixed_rejectedWithoutHeatmapQuery() {
        String mixedTarget = BOUND_TORN_USER_ID + QqCommandMessage.buildAtMarker(AT_TARGET_QQ);
        QqRecMsgSender sender = sender();

        BizException exception = assertThrows(BizException.class,
                () -> strategy.handle(GROUP_ID, sender, "用户#" + mixedTarget));

        assertEquals("参数有误", exception.getMsg());
        verify(heatmapService, never()).queryFactionHeatmap(anyLong(), any(ActivityQueryRange.class));
        verify(heatmapService, never()).queryPersonalHeatmap(anyLong(), any(ActivityQueryRange.class));
    }

    @Test
    @DisplayName("非法 at 标记返回参数有误且不调用任何热力图服务")
    void handle_invalidAtMarker_rejectedWithoutHeatmapQuery() {
        QqRecMsgSender sender = sender();
        String msg = "用户#" + QqCommandMessage.INVALID_AT_MARKER;

        BizException exception = assertThrows(BizException.class,
                () -> strategy.handle(GROUP_ID, sender, msg));

        assertEquals("参数有误", exception.getMsg());
        verify(heatmapService, never()).queryFactionHeatmap(anyLong(), any(ActivityQueryRange.class));
        verify(heatmapService, never()).queryPersonalHeatmap(anyLong(), any(ActivityQueryRange.class));
    }

    private void assertFormatIntro(String msg) {
        List<? extends QqMsgParam<?>> result = strategy.handle(GROUP_ID, sender(), msg);
        assertTrue(replyText(result).contains("查询格式举例"), "非法参数应返回格式介绍: " + msg);
    }

    private QqRecMsgSender sender() {
        QqRecMsgSender sender = new QqRecMsgSender();
        sender.setUserId(999L);
        return sender;
    }

    private PersonalActivityHeatmapVO noDataPersonalHeatmap() {
        PersonalActivityHeatmapVO heatmap = PersonalActivityHeatmapVO.empty("用户 [54321] 活跃度热力图");
        heatmap.setHasData(false);
        return heatmap;
    }

    private FactionActivityHeatmapVO noDataFactionHeatmap() {
        FactionActivityHeatmapVO heatmap = FactionActivityHeatmapVO.empty("帮派 [20465] 活跃度热力图");
        heatmap.setHasData(false);
        return heatmap;
    }

    private String replyText(List<? extends QqMsgParam<?>> result) {
        return ((TextQqMsg) result.getFirst()).getData().text();
    }
}
