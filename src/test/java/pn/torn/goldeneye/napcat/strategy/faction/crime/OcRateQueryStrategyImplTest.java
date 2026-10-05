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
import pn.torn.goldeneye.napcat.send.msg.param.ImageQqMsg;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionOcManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcSlotManager;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.torn.service.faction.oc.image.rate.OcRateTableData;
import pn.torn.goldeneye.torn.service.faction.oc.image.rate.OcRateTableDocumentAssembler;
import pn.torn.goldeneye.utils.image.document.*;
import pn.torn.goldeneye.utils.image.render.TableImageRenderer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * OC 成功率策略 at 用户目标调用链测试。
 *
 * @author Bai
 * @version 1.9.0
 * @since 2026.08.21
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OC成功率策略 at 用户目标测试")
class OcRateQueryStrategyImplTest {

    @Mock
    private TornFactionOcUserDAO ocUserDao;
    @Mock
    private TornSettingOcManager settingOcManager;
    @Mock
    private TornSettingOcSlotManager settingOcSlotManager;
    @Mock
    private TornSettingFactionOcManager settingFactionOcManager;
    @Mock
    private OcRateTableDocumentAssembler rateTableDocumentAssembler;
    @Mock
    private TableImageRenderer tableImageRenderer;
    @Mock
    private TornUserManager userManager;

    private OcRateQueryStrategyImpl strategy;

    @BeforeEach
    void setUp() {
        strategy = new OcRateQueryStrategyImpl(ocUserDao, settingOcManager, settingOcSlotManager,
                settingFactionOcManager, rateTableDocumentAssembler, tableImageRenderer);
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

    @Test
    @DisplayName("有记录时委托组装器与HTML渲染器并透传Base64")
    void handle_withRecords_delegatesToRenderer() {
        QqRecMsgSender sender = sender();
        TornUserDO user = user(12345L);
        user.setNickname("示例玩家");
        user.setFactionId(2095L);
        when(userManager.getUserById(12345L)).thenReturn(user);
        stubOcUserQuery(List.of(ocUserRecord()));
        when(settingOcManager.getList()).thenReturn(List.of());
        when(settingOcSlotManager.getList()).thenReturn(List.of());
        when(settingFactionOcManager.getSlotList()).thenReturn(List.of());
        when(rateTableDocumentAssembler.assemble(any(OcRateTableData.class))).thenReturn(document());
        when(tableImageRenderer.render(any(TableDocument.class))).thenReturn("PNG-BASE64");

        List<? extends QqMsgParam<?>> result = strategy.handle(0L, sender, "12345");

        ImageQqMsg imageMsg = (ImageQqMsg) result.getFirst();
        assertEquals("base64://PNG-BASE64", imageMsg.getData().file());
        verify(rateTableDocumentAssembler).assemble(any(OcRateTableData.class));
        verify(tableImageRenderer).render(any(TableDocument.class));
    }

    private void stubOcUserQueryEmpty() {
        stubOcUserQuery(List.of());
    }

    private void stubOcUserQuery(List<TornFactionOcUserDO> records) {
        LambdaQueryChainWrapper<TornFactionOcUserDO> query = mock(LambdaQueryChainWrapper.class);
        when(ocUserDao.lambdaQuery()).thenReturn(query);
        when(query.eq(any(), any())).thenReturn(query);
        when(query.orderByDesc(any(SFunction.class))).thenReturn(query);
        when(query.orderByAsc(any(SFunction.class))).thenReturn(query);
        when(query.list()).thenReturn(records);
    }

    private TornFactionOcUserDO ocUserRecord() {
        TornFactionOcUserDO rate = new TornFactionOcUserDO();
        rate.setRank(7);
        rate.setOcName("Blast from the Past");
        rate.setPosition("Picklock#1");
        rate.setPassRate(70);
        return rate;
    }

    private TableDocument document() {
        return new TableDocument("示例玩家的OC成功率", List.of(new TableRow(List.of(
                new TableCell("示例玩家的OC成功率", TableCellStyleEnum.TITLE, 1, 1,
                        TableTextOverflowEnum.WRAP)))), 1600, TableThemeEnum.OC_RATE.getDocumentType());
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
