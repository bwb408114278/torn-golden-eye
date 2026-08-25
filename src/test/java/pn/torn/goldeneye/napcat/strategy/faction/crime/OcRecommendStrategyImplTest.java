package pn.torn.goldeneye.napcat.strategy.faction.crime;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.faction.crime.TornFactionOcRefreshManager;
import pn.torn.goldeneye.torn.manager.faction.crime.msg.TornFactionOcMsgManager;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendationVO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcSlotDictBO;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.TornOcRecommendService;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OC推荐策略测试，验证禁用/成功率不足状态提示与推荐列表组合输出。
 *
 * @author Bai
 * @version 1.4.4
 * @since 2026.08.20
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OC推荐策略测试")
class OcRecommendStrategyImplTest {
    private static final long USER_ID = 100L;
    private static final long FACTION_ID = 2095L;

    @Mock
    private TornFactionOcRefreshManager ocRefreshManager;
    @Mock
    private TornOcRecommendService recommendService;
    @Mock
    private TornFactionOcMsgManager msgManager;
    @Mock
    private TornFactionOcDAO ocDao;
    @Mock
    private TornFactionOcSlotDAO slotDao;
    @Mock
    private TornUserManager userManager;

    private OcRecommendStrategyImpl strategy;
    private TornUserDO user;
    private TornFactionOcDO oc;
    private TornFactionOcSlotDO slot;

    @BeforeEach
    void setUp() {
        strategy = new OcRecommendStrategyImpl(ocRefreshManager, recommendService, msgManager, ocDao, slotDao);
        ReflectionTestUtils.setField(strategy, "userManager", userManager);
        user = new TornUserDO();
        user.setId(USER_ID);
        user.setFactionId(FACTION_ID);
        user.setNickname("测试用户");
        oc = new TornFactionOcDO();
        oc.setId(1L);
        oc.setFactionId(FACTION_ID);
        oc.setName("Break the Bank");
        oc.setRank(8);
        slot = new TornFactionOcSlotDO();
        slot.setId(11L);
        slot.setOcId(oc.getId());
        slot.setUserId(USER_ID);
        slot.setPosition("Thief#1");
        slot.setProgress(BigDecimal.ZERO);

        when(userManager.getUserById(USER_ID)).thenReturn(user);
        LambdaQueryChainWrapper<TornFactionOcSlotDO> slotQuery = mock(LambdaQueryChainWrapper.class);
        when(slotDao.lambdaQuery()).thenReturn(slotQuery);
        when(slotQuery.eq(any(), any())).thenReturn(slotQuery);
        when(slotQuery.in(any(), anyCollection())).thenReturn(slotQuery);
        when(slotQuery.one()).thenReturn(slot);
        when(ocDao.queryExecutingOc(FACTION_ID)).thenReturn(List.of(oc));
    }

    @Test
    @DisplayName("当前OC禁用且有正常推荐 → 禁用提示与推荐列表、Torn链接同时存在")
    void disabledCurrentOc_shouldCombineDisabledTipWithNormalRecommendation() {
        stubCurrentStatus(true, false, 80, 60, BigDecimal.TEN);
        TornFactionOcDO normalOc = new TornFactionOcDO();
        normalOc.setId(2L);
        normalOc.setFactionId(FACTION_ID);
        normalOc.setName("Normal OC");
        normalOc.setRank(5);
        TornFactionOcSlotDO normalSlot = new TornFactionOcSlotDO();
        normalSlot.setId(22L);
        normalSlot.setOcId(2L);
        normalSlot.setPosition("Thief#2");
        OcRecommendationVO normalRecommendation = new OcRecommendationVO(
                normalOc, normalSlot, BigDecimal.valueOf(90), "高成功率");
        when(recommendService.recommendOcForUser(user, 3, new OcSlotDictBO(oc, slot)))
                .thenReturn(List.of(normalRecommendation));
        when(msgManager.buildRecommendTable(anyString(), eq(FACTION_ID), anyList()))
                .thenReturn("tableBase64");

        List<? extends QqMsgParam<?>> messages = handleMessages();

        String message = collectText(messages);
        assertThat(messages).hasSize(3);
        assertThat(message)
                .contains("你加入了禁用的OC, 需要更换其他OC")
                .contains("推荐加入")
                .contains("Normal OC")
                .contains("Thief#2")
                .contains("crimeId=2")
                .doesNotContain("Break the Bank")
                .doesNotContain("Thief#1")
                .doesNotContain("成功率", "帮派要求");
        assertThat(((TextQqMsg) messages.getFirst()).getData().text())
                .contains("你加入了禁用的OC, 需要更换其他OC");
        verify(msgManager).buildRecommendTable(anyString(), eq(FACTION_ID), anyList());
    }

    @Test
    @DisplayName("当前OC禁用且无正常候选 → 保留禁用提示并明确说明没有正常OC")
    void disabledCurrentOc_noNormalCandidate_shouldShowDisabledAndNoNormalOcTip() {
        stubCurrentStatus(true, false, 80, 60, BigDecimal.TEN);
        when(recommendService.recommendOcForUser(user, 3, new OcSlotDictBO(oc, slot))).thenReturn(List.of());

        List<? extends QqMsgParam<?>> messages = handleMessages();

        String message = collectText(messages);
        assertThat(messages).hasSize(2);
        assertThat(message)
                .contains("你加入了禁用的OC, 需要更换其他OC")
                .contains("暂未找到可加入的正常OC")
                .doesNotContain("推荐加入")
                .doesNotContain("暂时没有合适加入的OC")
                .doesNotContain("成功率", "帮派要求");
    }

    @Test
    @DisplayName("当前岗位成功率不足且有推荐 → 状态提示与推荐列表、Torn链接同时存在")
    void insufficientCurrentOc_shouldCombineStatusWithRecommendation() {
        stubCurrentStatus(false, true, 50, 60, null);
        TornFactionOcSlotDO vacantSlot = new TornFactionOcSlotDO();
        vacantSlot.setId(12L);
        vacantSlot.setOcId(oc.getId());
        vacantSlot.setPosition("Thief#2");
        OcRecommendationVO recommendation = new OcRecommendationVO(
                oc, vacantSlot, BigDecimal.valueOf(90), "高成功率");
        when(recommendService.recommendOcForUser(user, 3, new OcSlotDictBO(oc, slot)))
                .thenReturn(List.of(recommendation));
        when(msgManager.buildRecommendTable(anyString(), eq(FACTION_ID), anyList()))
                .thenReturn("tableBase64");

        List<? extends QqMsgParam<?>> messages = handleMessages();

        String message = collectText(messages);
        assertThat(messages).hasSize(3);
        assertThat(message)
                .contains("当前岗位Thief#1成功率: 50, 帮派要求: 60")
                .contains("推荐加入")
                .contains("Break the Bank")
                .contains("Thief#2")
                .contains("crimeId=1")
                .doesNotContain("暂时没有合适加入的OC");
        assertThat(((TextQqMsg) messages.getFirst()).getData().text())
                .contains("当前岗位Thief#1成功率: 50, 帮派要求: 60");
        verify(msgManager).buildRecommendTable(anyString(), eq(FACTION_ID), anyList());
    }

    @Test
    @DisplayName("成功率不足、无推荐且有进度 → 提示找OC指挥官决定是否换队")
    void insufficientCurrentOc_noRecommendWithProgress_shouldShowCommanderTip() {
        slot.setProgress(BigDecimal.ONE);
        stubCurrentStatus(false, true, 50, 60, null);
        when(recommendService.recommendOcForUser(user, 3, new OcSlotDictBO(oc, slot))).thenReturn(List.of());

        List<? extends QqMsgParam<?>> messages = handleMessages();

        String message = collectText(messages);
        assertThat(messages).hasSize(2);
        assertThat(message)
                .contains("当前岗位Thief#1成功率: 50, 帮派要求: 60")
                .contains("本队暂无适合岗位, 请找OC指挥官决定是否换队")
                .doesNotContain("推荐加入")
                .doesNotContain("暂未找到成功率达标的岗位");
    }

    @Test
    @DisplayName("成功率不足、无推荐且无进度 → 提示未找到成功率达标的岗位")
    void insufficientCurrentOc_noRecommendWithoutProgress_shouldShowNoQualifyingTip() {
        stubCurrentStatus(false, true, 50, 60, null);
        when(recommendService.recommendOcForUser(user, 3, new OcSlotDictBO(oc, slot))).thenReturn(List.of());

        List<? extends QqMsgParam<?>> messages = handleMessages();

        String message = collectText(messages);
        assertThat(messages).hasSize(2);
        assertThat(message)
                .contains("当前岗位Thief#1成功率: 50, 帮派要求: 60")
                .contains("暂未找到成功率达标的岗位")
                .doesNotContain("推荐加入")
                .doesNotContain("OC指挥官");
    }

    @Test
    @DisplayName("当前岗位没有更高候选时输出当前最佳状态")
    void currentOcBest_shouldShowBestStatus() {
        stubCurrentStatus(false, false, 80, 60, BigDecimal.TEN);
        when(recommendService.recommendOcForUser(user, 3, new OcSlotDictBO(oc, slot))).thenReturn(List.of());

        String message = handleText();

        assertThat(message).contains("当前加入岗位已是最佳选择");
    }

    private void stubCurrentStatus(boolean disabled, boolean insufficient, Integer actual,
                                   Integer required, BigDecimal score) {
        when(recommendService.queryCurrentOcStatus(user, new OcSlotDictBO(oc, slot)))
                .thenReturn(new TornOcRecommendService.CurrentOcStatus(
                        true, disabled, insufficient, actual, required, score));
    }

    private String handleText() {
        QqRecMsgSender sender = new QqRecMsgSender();
        return ((TextQqMsg) strategy.handle(0L, sender, String.valueOf(USER_ID)).getFirst())
                .getData().text();
    }

    private List<? extends QqMsgParam<?>> handleMessages() {
        QqRecMsgSender sender = new QqRecMsgSender();
        return strategy.handle(0L, sender, String.valueOf(USER_ID));
    }

    private String collectText(List<? extends QqMsgParam<?>> messages) {
        StringBuilder builder = new StringBuilder();
        for (QqMsgParam<?> message : messages) {
            if (message instanceof TextQqMsg textQqMsg) {
                builder.append(textQqMsg.getData().text()).append(System.lineSeparator());
            }
        }
        return builder.toString();
    }
}
