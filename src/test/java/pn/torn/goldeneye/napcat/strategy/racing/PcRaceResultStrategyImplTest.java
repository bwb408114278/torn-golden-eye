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
    @Mock
    private PcRaceTextAssembler textAssembler;
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
        assertEquals("汇总文本", ((TextQqMsg) messages.get(1)).getData().text());
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
        when(textAssembler.assembleSummary(result)).thenReturn("汇总文本");
    }

    private PcRaceResultBO result() {
        return new PcRaceResultBO(RACE_ID, BUSINESS_DATE, LocalDateTime.of(2026, 1, 5, 0, 30),
                LocalDateTime.of(2026, 1, 5, 8, 30), List.of(), null, List.of(), 0, 0,
                new BigDecimal("0.00"), null);
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
