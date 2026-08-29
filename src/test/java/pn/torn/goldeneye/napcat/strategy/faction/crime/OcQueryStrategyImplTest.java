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
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.ImageQqMsg;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.faction.crime.msg.TornFactionOcMsgManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OC查询策略多级别查询测试
 *
 * @author Bai
 * @version 1.5.1
 * @since 2026.08.29
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OC查询策略测试")
class OcQueryStrategyImplTest {
    private static final long FACTION_ID = 2095L;
    private static final long SENDER_QQ = 999L;

    @Mock
    private TornSettingFactionManager settingFactionManager;
    @Mock
    private TornFactionOcMsgManager msgManager;
    @Mock
    private TornFactionOcDAO ocDao;
    @Mock
    private TornUserManager userManager;

    private OcQueryStrategyImpl strategy;

    @BeforeEach
    void setUp() {
        strategy = new OcQueryStrategyImpl(settingFactionManager, msgManager, ocDao);
        ReflectionTestUtils.setField(strategy, "userManager", userManager);
    }

    @Test
    @DisplayName("英文逗号分隔 → 一次查询多个级别并合并表格")
    void handle_multiRankWithEnglishComma() {
        stubCommon(List.of(buildOc(11L, 8), buildOc(12L, 9)));
        when(msgManager.buildOcTable(anyString(), anyList())).thenReturn("base64-table");

        List<? extends QqMsgParam<?>> result = strategy.handle(0L, sender(), "8,9");

        assertImageTable(result, "HP  8、9级执行中OC", List.of(11L, 12L));
    }

    @Test
    @DisplayName("中文逗号分隔 → 与英文逗号等价")
    void handle_multiRankWithChineseComma() {
        stubCommon(List.of(buildOc(11L, 8), buildOc(12L, 9)));
        when(msgManager.buildOcTable(anyString(), anyList())).thenReturn("base64-table");

        List<? extends QqMsgParam<?>> result = strategy.handle(0L, sender(), "8，9");

        assertImageTable(result, "HP  8、9级执行中OC", List.of(11L, 12L));
    }

    @Test
    @DisplayName("单个级别 → 行为与历史版本一致")
    void handle_singleRank() {
        stubCommon(List.of(buildOc(11L, 8)));
        when(msgManager.buildOcTable(anyString(), anyList())).thenReturn("base64-table");

        List<? extends QqMsgParam<?>> result = strategy.handle(0L, sender(), "8");

        assertImageTable(result, "HP  8级执行中OC", List.of(11L));
    }

    @Test
    @DisplayName("级别段非法或使用#分隔 → 返回错误提示且不查库")
    void handle_invalidRank() {
        assertInvalidParam("8#a");
        assertInvalidParam("8#9");
    }

    private void assertInvalidParam(String msg) {
        List<? extends QqMsgParam<?>> result = strategy.handle(0L, sender(), msg);

        assertEquals("需要输入正确的OC级别, 例如g#OC查询#7,8",
                ((TextQqMsg) result.getFirst()).getData().text());
        verifyNoInteractions(ocDao, msgManager);
    }

    private void assertImageTable(List<? extends QqMsgParam<?>> result, String expectedTitle,
                                  List<Long> expectedOcIds) {
        verify(msgManager).buildOcTable(eq(expectedTitle), argThat(ocList ->
                ocList.stream().map(TornFactionOcDO::getId).toList().equals(expectedOcIds)));
        assertEquals("base64://base64-table", ((ImageQqMsg) result.getFirst()).getData().file());
    }

    /**
     * 桩定发送人、帮派配置与OC链式查询公共依赖
     *
     * @param result 链式查询返回的OC列表
     */
    private void stubCommon(List<TornFactionOcDO> result) {
        TornUserDO senderUser = new TornUserDO();
        senderUser.setId(1L);
        senderUser.setFactionId(FACTION_ID);
        when(userManager.getUserByQq(SENDER_QQ)).thenReturn(senderUser);

        TornSettingFactionDO faction = new TornSettingFactionDO();
        faction.setFactionShortName("HP");
        when(settingFactionManager.getIdMap()).thenReturn(Map.of(FACTION_ID, faction));

        LambdaQueryChainWrapper<TornFactionOcDO> query = mock(LambdaQueryChainWrapper.class);
        when(ocDao.lambdaQuery()).thenReturn(query);
        when(query.in(any(SFunction.class), any(Collection.class))).thenReturn(query);
        when(query.in(any(SFunction.class), eq(TornOcStatusEnum.RECRUITING.getCode()),
                eq(TornOcStatusEnum.PLANNING.getCode()))).thenReturn(query);
        when(query.eq(any(SFunction.class), any())).thenReturn(query);
        when(query.orderByAsc(any(SFunction.class))).thenReturn(query);
        when(query.orderByDesc(any(SFunction.class))).thenReturn(query);
        when(query.list()).thenReturn(result);
    }

    private QqRecMsgSender sender() {
        QqRecMsgSender sender = new QqRecMsgSender();
        sender.setUserId(SENDER_QQ);
        return sender;
    }

    private TornFactionOcDO buildOc(long id, int rank) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(id);
        oc.setFactionId(FACTION_ID);
        oc.setName("Clinical Precision");
        oc.setRank(rank);
        return oc;
    }
}
