package pn.torn.goldeneye.napcat.strategy.racing;

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
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.torn.model.racing.view.PcRaceParticipantVO;
import pn.torn.goldeneye.torn.model.racing.view.PcRaceScoreBO;
import pn.torn.goldeneye.torn.service.racing.image.PcRaceTextAssembler;
import pn.torn.goldeneye.torn.service.racing.query.PcRaceQueryService;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * PC成绩指令目标解析与消息组装测试。
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PC成绩指令测试")
class PcRaceScoreStrategyImplTest {
    private static final long SENDER_QQ = 999L;
    private static final long USER_ID = 12345L;

    @Mock
    private PcRaceQueryService queryService;
    @Mock
    private PcRaceTextAssembler textAssembler;
    @Mock
    private TornUserManager userManager;

    private PcRaceScoreStrategyImpl strategy;

    @BeforeEach
    void setUp() {
        strategy = new PcRaceScoreStrategyImpl(queryService, textAssembler);
        ReflectionTestUtils.setField(strategy, "userManager", userManager);
    }

    @Test
    @DisplayName("无参数查询发送者本人")
    void handle_shouldQuerySenderWithoutParameter() {
        when(userManager.getUserByQq(SENDER_QQ)).thenReturn(user());
        when(queryService.buildScore(USER_ID)).thenReturn(score());
        when(textAssembler.assembleScore(any(PcRaceScoreBO.class))).thenReturn("成绩文本");

        List<? extends QqMsgParam<?>> messages = strategy.handle(0L, sender(), "");

        assertEquals("成绩文本", text(messages));
        verify(queryService).buildScore(USER_ID);
    }

    @Test
    @DisplayName("数字参数按Torn用户ID查询")
    void handle_shouldQueryByUserId() {
        when(userManager.getUserById(USER_ID)).thenReturn(user());
        when(queryService.buildScore(USER_ID)).thenReturn(score());
        when(textAssembler.assembleScore(any(PcRaceScoreBO.class))).thenReturn("成绩文本");

        strategy.handle(0L, sender(), String.valueOf(USER_ID));

        verify(queryService).buildScore(USER_ID);
    }

    @Test
    @DisplayName("at某人时按被at用户查询且声明支持at目标")
    void handle_shouldQueryAtTarget() {
        when(userManager.getUserByQq(SENDER_QQ)).thenReturn(user());
        when(queryService.buildScore(USER_ID)).thenReturn(score());
        when(textAssembler.assembleScore(any(PcRaceScoreBO.class))).thenReturn("成绩文本");

        strategy.handle(0L, sender(), QqCommandMessage.buildAtMarker(SENDER_QQ));

        verify(queryService).buildScore(USER_ID);
        assertTrue(strategy.supportsAtUserTarget());
    }

    @Test
    @DisplayName("无成绩记录时返回暂无提示且不组装文本")
    void handle_shouldReturnNoDataMessage() {
        when(userManager.getUserByQq(SENDER_QQ)).thenReturn(user());
        when(queryService.buildScore(USER_ID)).thenReturn(new PcRaceScoreBO(USER_ID, "昵称", List.of()));

        List<? extends QqMsgParam<?>> messages = strategy.handle(0L, sender(), "");

        assertEquals("暂无赛车成绩记录", text(messages));
        verifyNoInteractions(textAssembler);
    }

    private String text(List<? extends QqMsgParam<?>> messages) {
        return ((TextQqMsg) messages.getFirst()).getData().text();
    }

    private QqRecMsgSender sender() {
        QqRecMsgSender sender = new QqRecMsgSender();
        sender.setUserId(SENDER_QQ);
        return sender;
    }

    private TornUserDO user() {
        TornUserDO user = new TornUserDO();
        user.setId(USER_ID);
        user.setNickname("昵称");
        return user;
    }

    private PcRaceScoreBO score() {
        PcRaceParticipantVO participant = new PcRaceParticipantVO(USER_ID, "昵称", "PHN", 1, 1, "00:04:52.16",
                "00:26.45", false);
        return new PcRaceScoreBO(USER_ID, "昵称",
                List.of(new PcRaceScoreBO.Item(LocalDate.of(2026, 1, 4), participant)));
    }
}
