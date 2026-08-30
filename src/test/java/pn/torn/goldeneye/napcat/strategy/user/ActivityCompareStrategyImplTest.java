package pn.torn.goldeneye.napcat.strategy.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.torn.model.activity.ActivityComparisonHeatmapVO;
import pn.torn.goldeneye.torn.model.activity.ActivityQueryRange;
import pn.torn.goldeneye.torn.model.activity.ActivityQueryRangeModeEnum;
import pn.torn.goldeneye.torn.service.activity.ActivityHeatmapService;
import pn.torn.goldeneye.torn.service.activity.TornActivityCollectService;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 活跃度对比指令测试。
 *
 * <p>覆盖单帮派（缺省A方为所在帮派）与双帮派形态、双方相同时的提示文案、
 * 截止日期参数的范围传递，以及非法参数与未加入帮派的边界。</p>
 *
 * @author Bai
 * @version 1.5.2
 * @since 2026.08.29
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("活跃度对比指令测试")
class ActivityCompareStrategyImplTest {

    private static final long GROUP_ID = 111L;
    private static final long SENDER_QQ = 999L;
    private static final long OWN_FACTION_ID = 20465L;
    private static final long TARGET_FACTION_ID = 12345L;
    private static final long FACTION_A_ID = 111L;
    private static final long FACTION_B_ID = 222L;

    @Mock
    private TornUserManager userManager;
    @Mock
    private ActivityHeatmapService heatmapService;

    private ActivityCompareStrategyImpl strategy;

    @BeforeEach
    void setUp() {
        strategy = new ActivityCompareStrategyImpl(heatmapService);
        ReflectionTestUtils.setField(strategy, "userManager", userManager);
    }

    @Test
    @DisplayName("单帮派参数以所在帮派为A方对比目标帮派（默认最近28天）")
    void handle_singleFaction_comparesOwnFactionWithDefaultRange() {
        when(userManager.getUserByQq(SENDER_QQ)).thenReturn(senderBoundUser(OWN_FACTION_ID));
        when(heatmapService.compareFactions(eq(OWN_FACTION_ID), eq(TARGET_FACTION_ID),
                any(ActivityQueryRange.class))).thenReturn(noDataComparison());

        List<? extends QqMsgParam<?>> result = strategy.handle(GROUP_ID, sender(), String.valueOf(TARGET_FACTION_ID));

        assertEquals(ActivityHeatmapService.NO_DATA_MESSAGE, replyText(result));
        ArgumentCaptor<ActivityQueryRange> captor = ArgumentCaptor.forClass(ActivityQueryRange.class);
        verify(heatmapService).compareFactions(eq(OWN_FACTION_ID), eq(TARGET_FACTION_ID), captor.capture());
        assertEquals(ActivityQueryRangeModeEnum.DEFAULT, captor.getValue().mode());
        assertEquals(LocalDate.now(TornActivityCollectService.HEATMAP_ZONE), captor.getValue().endDate());
    }

    @Test
    @DisplayName("双帮派参数按参数顺序对比任意两个帮派")
    void handle_twoFactions_comparesByParamOrder() {
        when(heatmapService.compareFactions(eq(FACTION_A_ID), eq(FACTION_B_ID),
                any(ActivityQueryRange.class))).thenReturn(noDataComparison());

        List<? extends QqMsgParam<?>> result = strategy.handle(GROUP_ID, sender(),
                FACTION_A_ID + "#" + FACTION_B_ID);

        assertEquals(ActivityHeatmapService.NO_DATA_MESSAGE, replyText(result));
        verify(heatmapService).compareFactions(eq(FACTION_A_ID), eq(FACTION_B_ID),
                any(ActivityQueryRange.class));
        verify(userManager, never()).getUserByQq(anyLong());
    }

    @Test
    @DisplayName("双帮派参数携带截止日期时以截至范围对比")
    void handle_twoFactionsWithUntilDate_passesAnchoredRange() {
        when(heatmapService.compareFactions(eq(FACTION_A_ID), eq(FACTION_B_ID),
                any(ActivityQueryRange.class))).thenReturn(noDataComparison());

        strategy.handle(GROUP_ID, sender(), FACTION_A_ID + "#" + FACTION_B_ID + "#2026-08-01");

        ArgumentCaptor<ActivityQueryRange> captor = ArgumentCaptor.forClass(ActivityQueryRange.class);
        verify(heatmapService).compareFactions(eq(FACTION_A_ID), eq(FACTION_B_ID), captor.capture());
        assertEquals(ActivityQueryRangeModeEnum.UNTIL, captor.getValue().mode());
        assertEquals(LocalDate.of(2026, 8, 1), captor.getValue().endDate());
        assertEquals(LocalDate.of(2026, 7, 5), captor.getValue().startDate());
    }

    @Test
    @DisplayName("单帮派参数携带截止日期时以截至范围对比")
    void handle_singleFactionWithUntilDate_passesAnchoredRange() {
        when(userManager.getUserByQq(SENDER_QQ)).thenReturn(senderBoundUser(OWN_FACTION_ID));
        when(heatmapService.compareFactions(eq(OWN_FACTION_ID), eq(TARGET_FACTION_ID),
                any(ActivityQueryRange.class))).thenReturn(noDataComparison());

        strategy.handle(GROUP_ID, sender(), TARGET_FACTION_ID + "#2026-08-01");

        ArgumentCaptor<ActivityQueryRange> captor = ArgumentCaptor.forClass(ActivityQueryRange.class);
        verify(heatmapService).compareFactions(eq(OWN_FACTION_ID), eq(TARGET_FACTION_ID), captor.capture());
        assertEquals(ActivityQueryRangeModeEnum.UNTIL, captor.getValue().mode());
    }

    @Test
    @DisplayName("单帮派目标与所在帮派相同时返回提示且不调用对比服务")
    void handle_singleFactionSameAsOwn_returnsTipWithoutCompare() {
        when(userManager.getUserByQq(SENDER_QQ)).thenReturn(senderBoundUser(OWN_FACTION_ID));

        List<? extends QqMsgParam<?>> result = strategy.handle(GROUP_ID, sender(),
                String.valueOf(OWN_FACTION_ID));

        assertEquals("对比自己帮派是准备造反吗", replyText(result));
        verify(heatmapService, never()).compareFactions(anyLong(), anyLong(), any(ActivityQueryRange.class));
    }

    @Test
    @DisplayName("双帮派参数两个帮派相同时返回提示且不调用对比服务")
    void handle_twoSameFactions_returnsTipWithoutCompare() {
        List<? extends QqMsgParam<?>> result = strategy.handle(GROUP_ID, sender(),
                FACTION_A_ID + "#" + FACTION_A_ID);

        assertEquals("对比自己帮派是准备造反吗", replyText(result));
        verify(heatmapService, never()).compareFactions(anyLong(), anyLong(), any(ActivityQueryRange.class));
    }

    @Test
    @DisplayName("发送人未加入帮派时单帮派形态返回提示且不调用对比服务")
    void handle_singleFactionNotInFaction_returnsTipWithoutCompare() {
        when(userManager.getUserByQq(SENDER_QQ)).thenReturn(senderBoundUser(null));

        List<? extends QqMsgParam<?>> result = strategy.handle(GROUP_ID, sender(),
                String.valueOf(TARGET_FACTION_ID));

        assertEquals("你还没有加入帮派哦", replyText(result));
        verify(heatmapService, never()).compareFactions(anyLong(), anyLong(), any(ActivityQueryRange.class));
    }

    @Test
    @DisplayName("非法参数应返回格式说明且不调用对比服务")
    void handle_invalidParams_returnsFormatIntro() {
        assertFormatIntro("");
        assertFormatIntro("abc");
        assertFormatIntro(TARGET_FACTION_ID + "#abc#2026-08-01");
        assertFormatIntro(TARGET_FACTION_ID + "#2026/08/01");
        assertFormatIntro(TARGET_FACTION_ID + "#从#2026-08-01");
        assertFormatIntro(FACTION_A_ID + "#" + FACTION_B_ID + "#2026-08-01#多余");

        verify(heatmapService, never()).compareFactions(anyLong(), anyLong(), any(ActivityQueryRange.class));
    }

    private void assertFormatIntro(String msg) {
        List<? extends QqMsgParam<?>> result = strategy.handle(GROUP_ID, sender(), msg);
        assertTrue(replyText(result).contains("查询格式举例"), "非法参数应返回格式介绍: " + msg);
    }

    private QqRecMsgSender sender() {
        QqRecMsgSender sender = new QqRecMsgSender();
        sender.setUserId(SENDER_QQ);
        return sender;
    }

    private TornUserDO senderBoundUser(Long factionId) {
        TornUserDO user = new TornUserDO();
        user.setId(54321L);
        user.setFactionId(factionId);
        return user;
    }

    private ActivityComparisonHeatmapVO noDataComparison() {
        ActivityComparisonHeatmapVO vo = ActivityComparisonHeatmapVO.empty(
                FACTION_A_ID, "帮派A", FACTION_B_ID, "帮派B");
        vo.setHasData(false);
        return vo;
    }

    private String replyText(List<? extends QqMsgParam<?>> result) {
        return ((TextQqMsg) result.getFirst()).getData().text();
    }
}
