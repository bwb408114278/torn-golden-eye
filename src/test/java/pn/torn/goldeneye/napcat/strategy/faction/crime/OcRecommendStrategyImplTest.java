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
 * OC推荐策略测试，验证当前状态优先于普通空推荐提示。
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

        when(userManager.getUserById(USER_ID)).thenReturn(user);
        LambdaQueryChainWrapper<TornFactionOcSlotDO> slotQuery = mock(LambdaQueryChainWrapper.class);
        when(slotDao.lambdaQuery()).thenReturn(slotQuery);
        when(slotQuery.eq(any(), any())).thenReturn(slotQuery);
        when(slotQuery.in(any(), anyCollection())).thenReturn(slotQuery);
        when(slotQuery.one()).thenReturn(slot);
        when(ocDao.queryExecutingOc(FACTION_ID)).thenReturn(List.of(oc));
    }

    @Test
    @DisplayName("当前OC禁用时输出禁用状态而不是无推荐")
    void disabledCurrentOc_shouldShowDisabledStatus() {
        stubCurrentStatus(true, false, 80, 60, BigDecimal.TEN);
        when(recommendService.recommendOcForUser(user, 3, new OcSlotDictBO(oc, slot))).thenReturn(List.of());

        String message = handleText();

        assertThat(message).contains("当前加入的OC已被禁用");
        assertThat(message).doesNotContain("暂时没有合适加入的OC");
    }

    @Test
    @DisplayName("当前岗位成功率不足时输出实际值和要求值")
    void insufficientCurrentOc_shouldShowPassRateStatus() {
        stubCurrentStatus(false, true, 50, 60, null);
        when(recommendService.recommendOcForUser(user, 3, new OcSlotDictBO(oc, slot))).thenReturn(List.of());

        String message = handleText();

        assertThat(message).contains("当前岗位成功率不足").contains("当前50%", "要求60%");
        assertThat(message).doesNotContain("暂时没有合适加入的OC");
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
}
