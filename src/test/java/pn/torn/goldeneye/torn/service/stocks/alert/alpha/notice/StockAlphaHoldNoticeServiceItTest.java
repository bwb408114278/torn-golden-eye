package pn.torn.goldeneye.torn.service.stocks.alert.alpha.notice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeTypeEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.track.StockAlphaTrackRegistry;
import pn.torn.goldeneye.utils.image.render.html.PlaywrightBrowserManager;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * α继续持有通知真实PostgreSQL集成测试。
 * <p>
 * 验证"继续持有通知不占用批次维度":同一批次在两个决策日各写一条审计行时必须都落库成功
 * (批次维度部分唯一索引只服务买卖腿与换仓腿),且两条审计行的{@code batch_id}均为NULL,
 * 批次事实仍可从载荷快照复核;同一决策日重复写入只复用既有行。
 * 使用隔离决策业务日与 {@code @Transactional}+{@code @Rollback},不残留任何数据。
 *
 * @author Bai
 * @version 1.6.6
 * @since 2026.10.01
 */
@Tag("shared-db")
@SpringBootTest
@Transactional
@Rollback
@DisplayName("α继续持有通知真实PostgreSQL集成测试")
class StockAlphaHoldNoticeServiceItTest {
    /**
     * 隔离的第一决策业务日(远离生产日期)
     */
    private static final LocalDate FIRST_DECISION_DATE = LocalDate.of(2099, 6, 1);
    /**
     * 隔离的第二决策业务日
     */
    private static final LocalDate SECOND_DECISION_DATE = LocalDate.of(2099, 6, 2);
    /**
     * 第一决策日的执行桶起点
     */
    private static final LocalDateTime FIRST_EXECUTION_BAR = LocalDateTime.of(2099, 6, 1, 8, 30);
    /**
     * 第二决策日的执行桶起点
     */
    private static final LocalDateTime SECOND_EXECUTION_BAR = LocalDateTime.of(2099, 6, 2, 8, 30);

    @Autowired
    private StockAlphaHoldNoticeService holdNoticeService;
    @Autowired
    private TornStockNoticeAuditDAO noticeAuditDAO;
    /**
     * 表格图片渲染浏览器与通知审计的真实数据库行为无关,且需要下载/启动外部Chromium;
     * 测试上下文按既有集成测试口径以替身替换,不改变真实通知审计DAO与真实唯一索引语义。
     */
    @MockitoBean
    private PlaywrightBrowserManager playwrightBrowserManager;

    @Test
    @DisplayName("真实PG_同一批次跨两个决策日各写一条且batch_id均为空")
    void recordHoldNotice_sameBatchAcrossTwoDecisionDays_writesTwoRowsWithoutBatchId() {
        TornStockVirtualBatchDO batch = openBatch();

        holdNoticeService.recordHoldNotice(StockAlphaTrackRegistry.VIP_ALPHA, FIRST_DECISION_DATE, 10,
                FIRST_EXECUTION_BAR, batch);
        holdNoticeService.recordHoldNotice(StockAlphaTrackRegistry.VIP_ALPHA, SECOND_DECISION_DATE, 11,
                SECOND_EXECUTION_BAR, batch);

        TornStockNoticeAuditDO first = readBack(FIRST_DECISION_DATE);
        TornStockNoticeAuditDO second = readBack(SECOND_DECISION_DATE);
        assertNotNull(first, "第一决策日必须落一条继续持有通知");
        assertNotNull(second, "同一批次在第二决策日必须能再落一条,不受批次维度唯一键约束");
        assertNotEquals(first.getId(), second.getId());
        assertNull(first.getBatchId(), "审计行不得占用批次维度唯一键");
        assertNull(second.getBatchId(), "审计行不得占用批次维度唯一键");
        assertTrue(first.getPayloadSnapshot().contains("\"batchId\""), "批次事实必须固化在载荷中");
        assertTrue(first.getPayloadSnapshot().contains(batch.getBatchNo()), "载荷必须可复核批次编号");

        holdNoticeService.recordHoldNotice(StockAlphaTrackRegistry.VIP_ALPHA, FIRST_DECISION_DATE, 10,
                FIRST_EXECUTION_BAR, batch);

        TornStockNoticeAuditDO rewritten = readBack(FIRST_DECISION_DATE);
        assertEquals(first.getId(), rewritten.getId(), "同决策日同轨道必须复用既有行而不是新增");
        assertEquals(first.getNoticeNo(), rewritten.getNoticeNo());
    }

    /**
     * 按决策业务日与轨道回读继续持有通知审计行。
     *
     * @param decisionDate 决策业务日
     * @return 通知审计行;不存在时返回null
     */
    private TornStockNoticeAuditDO readBack(LocalDate decisionDate) {
        return noticeAuditDAO.selectBySummaryDateTypeAndTrack(decisionDate,
                StockNoticeTypeEnum.ALPHA_HOLD.getCode(), StockAlphaTrackRegistry.VIP_ALPHA.trackCode());
    }

    /**
     * 构建跨决策日保持开放的持仓批次。
     *
     * @return 开放持仓批次
     */
    private TornStockVirtualBatchDO openBatch() {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setId(410L);
        batch.setBatchNo("AB-2099-06-01-5001");
        batch.setStocksId(5001);
        batch.setStocksShortname("CNC");
        return batch;
    }
}
