package pn.torn.goldeneye.napcat.strategy.faction.crime.benefit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.ImageQqMsg;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcBenefitDAO;
import pn.torn.goldeneye.torn.model.faction.crime.income.OcBenefitRankingQuery;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * OC收益榜策略月份参数编排测试。
 *
 * <p>验证榜策略经基类解析后按目标月份构造整月查询范围（帮派榜、默认SMTH总榜），
 * 未来月份回复格式介绍且不触发查询。榜单SQL口径由{@code TornFactionOcBenefitMapperTest}保护，
 * 本测试是月份参数接线证据。</p>
 *
 * @author Bai
 * @version 1.5.2
 * @since 2026.08.30
 */
@SpringBootTest
@Tag("shared-db")
@DisplayName("OC收益榜策略月份参数编排测试")
class OcBenefitRankStrategyImplTest {
    @Autowired
    private OcBenefitRankStrategyImpl strategy;
    @MockitoSpyBean
    private TornFactionOcBenefitDAO benefitDao;

    @Test
    @DisplayName("帮派+历史月：按目标月份构造整月查询范围并渲染图片")
    void handle_factionAndHistoryMonth_queriesTargetMonth() {
        doReturn(List.of()).when(benefitDao).queryBenefitRanking(any());

        List<? extends QqMsgParam<?>> result = strategy.handle(0L, new QqRecMsgSender(),
                TornConstants.FACTION_PN_ID + "#2026-07");

        assertEquals(1, result.size());
        assertInstanceOf(ImageQqMsg.class, result.getFirst());
        ArgumentCaptor<OcBenefitRankingQuery> queryCaptor = ArgumentCaptor.forClass(OcBenefitRankingQuery.class);
        verify(benefitDao).queryBenefitRanking(queryCaptor.capture());
        OcBenefitRankingQuery query = queryCaptor.getValue();
        assertEquals(TornConstants.FACTION_PN_ID, query.getFactionId());
        assertEquals(LocalDateTime.of(2026, 7, 1, 0, 0, 0), query.getFromDate());
        assertEquals(LocalDateTime.of(2026, 7, 31, 23, 59, 59), query.getToDate());
    }

    @Test
    @DisplayName("单历史月段默认SMTH总榜并按目标月份查询")
    void handle_singleHistoryMonth_defaultsToSmth() {
        doReturn(List.of()).when(benefitDao).queryBenefitRanking(any());

        strategy.handle(0L, new QqRecMsgSender(), "2026-07");

        ArgumentCaptor<OcBenefitRankingQuery> queryCaptor = ArgumentCaptor.forClass(OcBenefitRankingQuery.class);
        verify(benefitDao).queryBenefitRanking(queryCaptor.capture());
        assertEquals(0L, queryCaptor.getValue().getFactionId());
        assertEquals(LocalDateTime.of(2026, 7, 1, 0, 0, 0), queryCaptor.getValue().getFromDate());
    }

    @Test
    @DisplayName("未来月份回复格式介绍且不触发查询")
    void handle_futureMonth_returnsFormatIntro() {
        List<? extends QqMsgParam<?>> result = strategy.handle(0L, new QqRecMsgSender(), "同期#2099-01");

        assertEquals(1, result.size());
        assertInstanceOf(TextQqMsg.class, result.getFirst());
        assertEquals("参数有误，正确格式：g#OC收益榜(#帮派ID|同期)(#yyyy-MM)，月份不得晚于当月",
                ((TextQqMsg) result.getFirst()).getData().text());
        verify(benefitDao, never()).queryBenefitRanking(any());
        verify(benefitDao, never()).queryCohortBenefitRanking(any());
    }
}
