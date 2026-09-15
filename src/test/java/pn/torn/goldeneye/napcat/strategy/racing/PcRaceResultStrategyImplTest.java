package pn.torn.goldeneye.napcat.strategy.racing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.ImageQqMsg;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.torn.model.racing.view.PcRaceParticipantVO;
import pn.torn.goldeneye.torn.model.racing.view.PcRaceResultBO;
import pn.torn.goldeneye.torn.service.racing.image.PcRaceDocumentAssembler;
import pn.torn.goldeneye.torn.service.racing.image.PcRaceTextAssembler;
import pn.torn.goldeneye.torn.service.racing.query.PcRaceQueryService;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.image.document.*;
import pn.torn.goldeneye.utils.image.render.TableImageRenderer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * PC结果指令参数解析与消息组装测试。
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PC结果指令测试")
class PcRaceResultStrategyImplTest {
    private static final long RACE_ID = 1000L;
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 1, 5);

    @Mock
    private PcRaceQueryService queryService;
    @Mock
    private PcRaceDocumentAssembler documentAssembler;
    private final PcRaceTextAssembler textAssembler = new PcRaceTextAssembler();
    @Mock
    private TableImageRenderer imageRenderer;

    private PcRaceResultStrategyImpl strategy;

    @BeforeEach
    void setUp() {
        strategy = new PcRaceResultStrategyImpl(queryService, documentAssembler, textAssembler, imageRenderer);
    }

    @Test
    @DisplayName("无参数查询当前Torn日的前一天并同时返回图片与汇总文本")
    void handle_shouldQueryYesterdayWithoutParameter() {
        PcRaceResultBO result = result();
        when(queryService.buildResultByBusinessDate(any(LocalDate.class))).thenReturn(result);
        stubRender(result);

        List<? extends QqMsgParam<?>> messages = strategy.handle(0L, sender(), "");

        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(queryService).buildResultByBusinessDate(captor.capture());
        assertEquals(DateTimeUtils.getTornLocalDate().minusDays(1), captor.getValue());
        assertEquals(2, messages.size());
        assertInstanceOf(ImageQqMsg.class, messages.getFirst());
        assertEquals("base64://image", ((ImageQqMsg) messages.getFirst()).getData().file());
        assertEquals("""
                🏁 SMTHPC 2026-01-05
                最快圈：Baby [2554043] (第47名) 02:41.32
                参赛率：2/3 = 66.67%
                💥 Crash：无
                🎲 抽奖：Jubelie [2554044] (第71名)""", summary(messages));
    }

    @Test
    @DisplayName("有撞车选手时按昵称、用户ID与名次罗列，最快圈与抽奖无数据时显示无")
    void handle_shouldListCrashedParticipantsWithUserId() {
        PcRaceParticipantVO crashedWithPosition = new PcRaceParticipantVO(2554045L, "Bar", "PHN", null, 12,
                null, null, true);
        PcRaceParticipantVO crashedWithoutPosition = new PcRaceParticipantVO(2554046L, "Baz", "PHN", null, null,
                null, null, true);
        PcRaceResultBO result = new PcRaceResultBO(RACE_ID, BUSINESS_DATE, "Docks",
                LocalDateTime.of(2026, 1, 5, 0, 30), LocalDateTime.of(2026, 1, 5, 8, 30), List.of(),
                null, List.of(crashedWithPosition, crashedWithoutPosition), 0, 2,
                new BigDecimal("0.00"), null);
        when(queryService.buildResultByBusinessDate(any(LocalDate.class))).thenReturn(result);
        stubRender(result);

        String summary = summary(strategy.handle(0L, sender(), ""));

        assertTrue(summary.contains("Crash：Bar [2554045] (第12名)、Baz [2554046] (未完赛)"));
        assertTrue(summary.contains("最快圈：无"));
        assertTrue(summary.contains("抽奖：无"));
    }

    @Test
    @DisplayName("日期参数按业务日期查询")
    void handle_shouldQueryByBusinessDate() {
        PcRaceResultBO result = result();
        when(queryService.buildResultByBusinessDate(BUSINESS_DATE)).thenReturn(result);
        stubRender(result);

        strategy.handle(0L, sender(), DateTimeUtils.convertToString(BUSINESS_DATE));

        verify(queryService).buildResultByBusinessDate(BUSINESS_DATE);
    }

    @Test
    @DisplayName("纯数字参数按赛事ID查询")
    void handle_shouldQueryByRaceId() {
        PcRaceResultBO result = result();
        when(queryService.buildResultByRaceId(RACE_ID)).thenReturn(result);
        stubRender(result);

        strategy.handle(0L, sender(), String.valueOf(RACE_ID));

        verify(queryService).buildResultByRaceId(RACE_ID);
    }

    @Test
    @DisplayName("非法参数与超出long范围的数字返回参数有误")
    void handle_shouldRejectInvalidParameter() {
        assertEquals("参数有误", text(strategy.handle(0L, sender(), "abc")));
        assertEquals("参数有误", text(strategy.handle(0L, sender(), "99999999999999999999")));
        assertEquals("参数有误", text(strategy.handle(0L, sender(), "2026/01/05")));
        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("未命中赛事返回尚未抓取提示且不渲染图片")
    void handle_shouldReturnMissingDataMessage() {
        when(queryService.buildResultByBusinessDate(any(LocalDate.class))).thenReturn(null);

        List<? extends QqMsgParam<?>> messages = strategy.handle(0L, sender(), "");

        assertTrue(text(messages).contains("未查询到SMTHPC赛事数据"));
        verifyNoInteractions(imageRenderer, documentAssembler);
    }

    private void stubRender(PcRaceResultBO result) {
        when(documentAssembler.assemble(result)).thenReturn(document());
        when(imageRenderer.render(any(TableDocument.class))).thenReturn("image");
    }

    private PcRaceResultBO result() {
        PcRaceParticipantVO fastestLap = participant(2554043L, "Baby", 47, "02:41.32");
        PcRaceParticipantVO drawWinner = participant(2554044L, "Jubelie", 71, null);
        return new PcRaceResultBO(RACE_ID, BUSINESS_DATE, "Docks", LocalDateTime.of(2026, 1, 5, 0, 30),
                LocalDateTime.of(2026, 1, 5, 8, 30), List.of(fastestLap, drawWinner), fastestLap, List.of(),
                2, 3, new BigDecimal("66.67"), drawWinner);
    }

    private PcRaceParticipantVO participant(long userId, String nickname, Integer position,
                                            String bestLapTimeText) {
        return new PcRaceParticipantVO(userId, nickname, "PHN", null, position, null, bestLapTimeText, false);
    }

    /**
     * 读取指令返回的汇总文本消息。
     *
     * @param messages 指令返回的消息列表
     * @return 汇总文本
     */
    private String summary(List<? extends QqMsgParam<?>> messages) {
        return ((TextQqMsg) messages.get(1)).getData().text();
    }

    private TableDocument document() {
        return new TableDocument("标题", List.of(new TableRow(List.of(
                TableCell.plainText("单元", TableCellStyleEnum.BODY, 1, 1, TableTextOverflowEnum.WRAP)))),
                1600, TableThemeEnum.PC_RACE.getDocumentType());
    }

    private String text(List<? extends QqMsgParam<?>> messages) {
        return ((TextQqMsg) messages.getFirst()).getData().text();
    }

    private QqRecMsgSender sender() {
        QqRecMsgSender sender = new QqRecMsgSender();
        sender.setUserId(999L);
        return sender;
    }
}
