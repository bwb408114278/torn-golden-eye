package pn.torn.goldeneye.torn.service.stocks.alert.notice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.*;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeComposeService.ComposedMessage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 股票通知组合服务单元测试 - 覆盖中文消息格式化、枚举映射、消息合并与优先级排序
 * <p>
 * 被测对象 {@link StockNoticeComposeService} 为纯计算服务,无 DAO 依赖,直接 new 实例。
 * 测试范围:
 * <ul>
 *   <li>买入消息: 必要字段完整性、策略/风格/成熟度/风险的中文映射、最高跟随价计算、槽位展示格式</li>
 *   <li>卖出消息: 必要字段完整性、风险退出不称止盈、净收益正负号格式化、持有时间天/小时格式化</li>
 *   <li>合并与排序: 风险卖出优先于其他卖出优先于买入、同类型超过3个动作拆分为续报、买入消息不含英文编码</li>
 * </ul>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.07.25
 */
@DisplayName("股票通知组合服务测试")
class StockNoticeComposeServiceTest {

    private StockNoticeComposeService service;

    @BeforeEach
    void setUp() {
        service = new StockNoticeComposeService();
    }

    // ==================== 买入消息 ====================

    @Nested
    @DisplayName("买入消息组合")
    class ComposeBuyMessage {

        @Test
        @DisplayName("正常构建_包含全部必要字段")
        void composeBuyMessage_normalBuild_containsAllRequiredFields() {
            TornStockVirtualBatchDO batch = buildBuyBatch();

            String text = service.composeBuyMessage(batch, 2);

            assertNotNull(text);
            assertTrue(text.contains("批次 B-2026-001"), "应包含批次号");
            assertTrue(text.contains("股票：测试股票A"), "应包含股票简称");
            assertTrue(text.contains("买入策略："), "应包含买入策略");
            assertTrue(text.contains("系统参考买价：$100.00"), "应包含系统参考买价");
            assertTrue(text.contains("股票风格："), "应包含股票风格");
            assertTrue(text.contains("成熟度："), "应包含成熟度");
            assertTrue(text.contains("风险等级："), "应包含风险等级");
            assertTrue(text.contains("建议跟随截止："), "应包含建议跟随截止时间");
            assertTrue(text.contains("最高建议跟随价：$"), "应包含最高建议跟随价");
            assertTrue(text.contains("当前组合槽位："), "应包含当前组合槽位");
            assertTrue(text.contains("本消息属于系统虚拟组合"), "应包含虚拟组合声明");
        }

        @Test
        @DisplayName("验证中文映射_策略风格成熟度风险均为中文")
        void composeBuyMessage_chineseMapping_allEnumsInChinese() {
            TornStockVirtualBatchDO batch = buildBuyBatch();

            String text = service.composeBuyMessage(batch, 1);

            assertTrue(text.contains(StockBuyStrategyEnum.RANGE_LOWER_BUY.getChineseDisplay()),
                    "买入策略应映射为中文: 区间下沿买入");
            assertTrue(text.contains(StockStrategyFitEnum.RANGING.getChineseDisplay()),
                    "股票风格应映射为中文: 区间震荡");
            assertTrue(text.contains(StockMaturityEnum.M3_SEASONED.getChineseDisplay()),
                    "成熟度应映射为中文: 较成熟");
            assertTrue(text.contains(StockRiskLevelEnum.MEDIUM.getChineseDisplay()),
                    "风险等级应映射为中文: 中等风险");
        }

        @Test
        @DisplayName("验证followMaxPrice_等于entryPrice乘1.0015")
        void composeBuyMessage_followMaxPriceEqualsEntryPriceTimesMultiplier() {
            BigDecimal entryPrice = new BigDecimal("100.00");
            TornStockVirtualBatchDO batch = buildBuyBatch();
            batch.setEntryReferencePrice(entryPrice);

            String expected = entryPrice
                    .multiply(StockNoticeComposeService.FOLLOW_PRICE_MULTIPLIER)
                    .setScale(2, RoundingMode.HALF_UP)
                    .toPlainString();

            String text = service.composeBuyMessage(batch, 1);

            assertTrue(text.contains("最高建议跟随价：$" + expected),
                    "最高跟随价应等于 entryPrice × 1.0015 = " + expected);
        }

        @Test
        @DisplayName("验证槽位显示_格式为batchSlotNo/5")
        void composeBuyMessage_slotDisplayFormattedAsOccupiedOverTotal() {
            TornStockVirtualBatchDO batch = buildBuyBatch();

            String text = service.composeBuyMessage(batch, 3);

            assertTrue(text.contains("当前组合槽位：2 / 5"),
                    "槽位显示格式应为 'batch.slotNo / 5' (批次槽位2/总数5)");
        }
    }

    // ==================== 卖出消息 ====================

    @Nested
    @DisplayName("卖出消息组合")
    class ComposeSellMessage {

        @Test
        @DisplayName("正常构建_包含全部必要字段")
        void composeSellMessage_normalBuild_containsAllRequiredFields() {
            TornStockVirtualBatchDO batch = buildSellBatch(StockCloseTypeEnum.CLOSED_TARGET.getCode(),
                    new BigDecimal("0.008"));

            String text = service.composeSellMessage(batch);

            assertNotNull(text);
            assertTrue(text.contains("批次 B-2026-001"), "应包含原买入批次号");
            assertTrue(text.contains("股票：测试股票A"), "应包含股票简称");
            assertTrue(text.contains("原买入策略："), "应包含原买入策略");
            assertTrue(text.contains("系统参考买价：$100.00"), "应包含系统参考买价");
            assertTrue(text.contains("系统参考卖价：$"), "应包含系统参考卖价");
            assertTrue(text.contains("扣除0.1%卖出费后净收益："), "应包含净收益");
            assertTrue(text.contains("系统持有时间："), "应包含系统持有时间");
            assertTrue(text.contains("关闭原因："), "应包含关闭原因");
            assertTrue(text.contains("本卖出仅对应批次 B-2026-001"), "应包含本卖出仅对应批次声明");
        }

        @Test
        @DisplayName("风险退出_不包含止盈字样")
        void composeSellMessage_riskExit_noProfitTakingText() {
            TornStockVirtualBatchDO batch = buildSellBatch(StockCloseTypeEnum.CLOSED_RISK.getCode(),
                    new BigDecimal("-0.015"));

            String text = service.composeSellMessage(batch);

            assertTrue(text.contains(StockCloseTypeEnum.CLOSED_RISK.getChineseDisplay()),
                    "风险退出应展示中文: 风险退出");
            assertFalse(text.contains("止盈"), "风险退出不能称为止盈");
        }

        @Test
        @DisplayName("净收益格式化_正收益显示加号")
        void composeSellMessage_positiveReturn_showsPlusSign() {
            TornStockVirtualBatchDO batch = buildSellBatch(StockCloseTypeEnum.CLOSED_TARGET.getCode(),
                    new BigDecimal("0.008"));

            String text = service.composeSellMessage(batch);

            assertTrue(text.contains("+0.80%"), "正收益应显示加号: +0.80%");
            assertFalse(text.contains("-0.80%"), "正收益不应出现减号");
        }

        @Test
        @DisplayName("净收益格式化_负收益显示减号")
        void composeSellMessage_negativeReturn_showsMinusSign() {
            TornStockVirtualBatchDO batch = buildSellBatch(StockCloseTypeEnum.CLOSED_RISK.getCode(),
                    new BigDecimal("-0.015"));

            String text = service.composeSellMessage(batch);

            assertTrue(text.contains("-1.50%"), "负收益应显示减号: -1.50%");
            assertFalse(text.contains("+1.50%"), "负收益不应出现加号");
        }

        @Test
        @DisplayName("持有时间格式化_正确显示天和小时")
        void composeSellMessage_holdDuration_formattedAsDaysAndHours() {
            TornStockVirtualBatchDO batch = buildSellBatch(StockCloseTypeEnum.CLOSED_TIME.getCode(),
                    new BigDecimal("0.002"));
            // 入场 3天5小时前 -> 持有时间 "3天5小时"
            LocalDateTime exitTime = LocalDateTime.of(2026, 7, 25, 12, 0);
            LocalDateTime entryTime = exitTime.minusDays(3).minusHours(5);
            batch.setEntryTime(entryTime);
            batch.setExitTime(exitTime);

            String text = service.composeSellMessage(batch);

            assertTrue(text.contains("系统持有时间：3天5小时"),
                    "持有时间应格式化为 '3天5小时'");
        }

        @Test
        @DisplayName("数据异常关闭_使用独立标题且不使用普通SELL原因或止盈文案")
        void composeSellMessage_adminClose_usesDisasterTitle() {
            TornStockVirtualBatchDO batch = buildSellBatch(StockCloseTypeEnum.CLOSED_TARGET.getCode(),
                    new BigDecimal("0.008"));
            batch.setBatchStatus(StockBatchStatusEnum.ADMIN_CLOSED.getCode());
            batch.setExpectedExitBarTime(LocalDateTime.of(2026, 7, 25, 12, 0));

            String text = service.composeSellMessage(batch);

            assertTrue(text.startsWith("【系统虚拟组合｜数据异常关闭】#B-2026-001"),
                    "数据异常关闭应使用独立标题");
            assertTrue(text.contains("原退出信号已触发，但预期成交bar缺失"), "应说明预期成交bar缺失");
            assertTrue(text.contains("原退出原因：达到目标收益"), "应保留原退出原因");
            assertTrue(text.contains("数据恢复后首个可用参考价：$100.80"), "应展示恢复后首个可用参考价");
            assertTrue(text.contains("本次为系统风险/管理关闭，不代表在该价格形成了原策略的准时卖出"),
                    "应说明不代表原策略准时卖出");
            assertTrue(text.contains("未跟随原BUY的成员无需操作"), "应包含未跟随无需操作声明");
            assertFalse(text.contains("系统参考卖价"), "灾难关闭消息不应伪装成普通策略卖出");
            assertFalse(text.contains("本卖出仅对应批次"), "灾难关闭消息不应使用普通SELL模板");
            assertFalse(text.contains("止盈"), "灾难关闭消息不得使用止盈文案");
        }

        @Test
        @DisplayName("P2-1_灾难关闭消息优先使用originalExitReason且与exitReason不一致时以原因为准")
        void composeDisasterCloseMessage_originalExitReasonPreferred_whenMismatch() {
            TornStockVirtualBatchDO batch = buildSellBatch(StockCloseTypeEnum.CLOSED_TARGET.getCode(),
                    new BigDecimal("0.008"));
            batch.setBatchStatus(StockBatchStatusEnum.ADMIN_CLOSED.getCode());
            batch.setExpectedExitBarTime(LocalDateTime.of(2026, 7, 25, 12, 0));
            // 原退出事实为CLOSED_RISK,兼容字段exitReason为CLOSED_TARGET: 必须展示originalExitReason
            batch.setOriginalExitReason(StockCloseTypeEnum.CLOSED_RISK.getCode());
            batch.setExitReason(StockCloseTypeEnum.CLOSED_TARGET.getCode());

            String text = service.composeSellMessage(batch);

            assertTrue(text.contains("原退出原因：风险退出"),
                    "两字段不一致时必须以originalExitReason(风险退出)为准,实际: " + text);
            assertFalse(text.contains("原退出原因：达到目标收益"),
                    "不得以兼容字段exitReason(达到目标收益)为权威来源");
        }

        @Test
        @DisplayName("P2-1_两字段一致时按originalExitReason展示")
        void composeDisasterCloseMessage_originalExitReasonMatches_exitReason() {
            TornStockVirtualBatchDO batch = buildSellBatch(StockCloseTypeEnum.CLOSED_RANGE.getCode(),
                    new BigDecimal("0.008"));
            batch.setBatchStatus(StockBatchStatusEnum.ADMIN_CLOSED.getCode());
            batch.setExpectedExitBarTime(LocalDateTime.of(2026, 7, 25, 12, 0));
            batch.setOriginalExitReason(StockCloseTypeEnum.CLOSED_RANGE.getCode());

            String text = service.composeSellMessage(batch);

            assertTrue(text.contains("原退出原因：区间恢复退出"),
                    "一致时按originalExitReason展示,实际: " + text);
        }

        @Test
        @DisplayName("P2-1_originalExitReason为空的历史兼容_回退exitReason展示")
        void composeDisasterCloseMessage_originalExitReasonNull_fallsBackToExitReason() {
            TornStockVirtualBatchDO batch = buildSellBatch(StockCloseTypeEnum.CLOSED_TARGET.getCode(),
                    new BigDecimal("0.008"));
            batch.setBatchStatus(StockBatchStatusEnum.ADMIN_CLOSED.getCode());
            batch.setExpectedExitBarTime(LocalDateTime.of(2026, 7, 25, 12, 0));
            batch.setOriginalExitReason(null);

            String text = service.composeSellMessage(batch);

            assertTrue(text.contains("原退出原因：达到目标收益"),
                    "历史记录缺originalExitReason时回退exitReason展示,实际: " + text);
        }
    }

    // ==================== 合并与优先级排序 ====================

    @Nested
    @DisplayName("合并与优先级排序")
    class ComposeAndMergeNotices {

        @Test
        @DisplayName("按优先级排序_风险卖出优先")
        void composeAndMergeNotices_priorityOrder_riskSellFirst() {
            // 构造: 买入 + 普通卖出 + 风险卖出,预期排序 风险卖出 -> 普通卖出 -> 买入
            TornStockVirtualBatchDO buyBatch = buildBuyBatch();
            buyBatch.setBatchNo("B-BUY");

            TornStockVirtualBatchDO targetSellBatch = buildSellBatch(StockCloseTypeEnum.CLOSED_TARGET.getCode(),
                    new BigDecimal("0.008"));
            targetSellBatch.setBatchNo("B-TGT");

            TornStockVirtualBatchDO riskSellBatch = buildSellBatch(StockCloseTypeEnum.CLOSED_RISK.getCode(),
                    new BigDecimal("-0.015"));
            riskSellBatch.setBatchNo("B-RISK");

            TornStockNoticeAuditDO buyNotice = buildNotice(1L, 101L, "BUY");
            TornStockNoticeAuditDO targetSellNotice = buildNotice(2L, 102L, "SELL");
            TornStockNoticeAuditDO riskSellNotice = buildNotice(3L, 103L, "SELL");

            Map<Long, TornStockVirtualBatchDO> batchMap = Map.of(
                    101L, buyBatch,
                    102L, targetSellBatch,
                    103L, riskSellBatch
            );

            List<ComposedMessage> result = service.composeAndMergeNotices(
                    List.of(buyNotice, targetSellNotice, riskSellNotice), batchMap);

            // 两个SELL同类型合并为1条消息(含风险卖出+普通卖出),BUY单独1条 -> 共2条
            assertEquals(2, result.size(), "2个SELL合并为1条 + 1个BUY = 共2条消息");
            // 首条为合并卖出消息(风险卖出优先排序)
            ComposedMessage sellMessage = result.getFirst();
            assertEquals(List.of(3L, 2L), sellMessage.noticeIds(),
                    "合并卖出消息noticeIds应为风险卖出(3)在前、普通卖出(2)在后");
            String sellText = sellMessage.text();
            assertTrue(sellText.contains("风险退出"), "卖出消息应含风险退出");
            assertTrue(sellText.contains("达到目标收益"), "卖出消息应含达到目标收益");
            // 风险卖出文本位置应早于普通卖出文本(优先级排序体现在内容顺序上)
            assertTrue(sellText.indexOf("风险退出") < sellText.indexOf("达到目标收益"),
                    "风险卖出内容应排在普通卖出之前");
            // 末条为买入消息
            ComposedMessage buyMessage = result.get(1);
            assertTrue(buyMessage.text().contains("买入策略"), "末条应为买入消息");
            assertEquals(List.of(1L), buyMessage.noticeIds(), "末条noticeIds应为买入通知ID");
        }

        @Test
        @DisplayName("超过3个动作_拆分为续报")
        void composeAndMergeNotices_exceedsMaxActions_splitIntoFollowUps() {
            // 构造5个风险卖出通知,应拆分为 3 + 2 两条消息
            TornStockNoticeAuditDO n1 = buildNotice(11L, 201L, "SELL");
            TornStockNoticeAuditDO n2 = buildNotice(12L, 202L, "SELL");
            TornStockNoticeAuditDO n3 = buildNotice(13L, 203L, "SELL");
            TornStockNoticeAuditDO n4 = buildNotice(14L, 204L, "SELL");
            TornStockNoticeAuditDO n5 = buildNotice(15L, 205L, "SELL");

            Map<Long, TornStockVirtualBatchDO> batchMap = Map.of(
                    201L, buildSellBatch(StockCloseTypeEnum.CLOSED_RISK.getCode(), new BigDecimal("-0.015")),
                    202L, buildSellBatch(StockCloseTypeEnum.CLOSED_RISK.getCode(), new BigDecimal("-0.016")),
                    203L, buildSellBatch(StockCloseTypeEnum.CLOSED_RISK.getCode(), new BigDecimal("-0.017")),
                    204L, buildSellBatch(StockCloseTypeEnum.CLOSED_RISK.getCode(), new BigDecimal("-0.018")),
                    205L, buildSellBatch(StockCloseTypeEnum.CLOSED_RISK.getCode(), new BigDecimal("-0.019"))
            );

            List<ComposedMessage> result = service.composeAndMergeNotices(
                    List.of(n1, n2, n3, n4, n5), batchMap);

            assertEquals(2, result.size(), "5个动作超过3个上限应拆分为2条消息");
            assertEquals(3, result.get(0).noticeIds().size(), "首条应含3个动作");
            assertEquals(2, result.get(1).noticeIds().size(), "续报应含2个动作");
            assertTrue(result.get(1).text().contains("（续）"), "续报消息应含（续）后缀");
        }

        @Test
        @DisplayName("买入消息不含英文编码")
        void composeAndMergeNotices_buyMessageNoEnglishCodes() {
            TornStockVirtualBatchDO buyBatch = buildBuyBatch();
            buyBatch.setBatchNo("B-ENC-001");
            TornStockNoticeAuditDO buyNotice = buildNotice(21L, 301L, "BUY");

            List<ComposedMessage> result = service.composeAndMergeNotices(
                    List.of(buyNotice), Map.of(301L, buyBatch));

            assertEquals(1, result.size());
            String text = result.getFirst().text();
            assertFalse(text.contains("RANGE_LOWER_BUY"), "买入消息不应含策略英文编码 RANGE_LOWER_BUY");
            assertFalse(text.contains("RANGING"), "买入消息不应含风格英文编码 RANGING");
            assertFalse(text.contains("M3_SEASONED"), "买入消息不应含成熟度英文编码 M3_SEASONED");
            assertFalse(text.contains("MEDIUM"), "买入消息不应含风险英文编码 MEDIUM");
        }
    }

    // ==================== 测试数据 Helper ====================

    /**
     * 构建买入批次测试数据(未平仓状态)。
     *
     * @return 预设字段的买入批次DO
     */
    private static TornStockVirtualBatchDO buildBuyBatch() {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setId(101L);
        batch.setBatchNo("B-2026-001");
        batch.setStocksId(1);
        batch.setStocksShortname("测试股票A");
        batch.setPrimaryStrategy(StockBuyStrategyEnum.RANGE_LOWER_BUY.getCode());
        batch.setEntryReferencePrice(new BigDecimal("100.00"));
        batch.setEntryTime(LocalDateTime.of(2026, 7, 25, 9, 0));
        batch.setStylePrior(StockStrategyFitEnum.RANGING.getCode());
        batch.setStyleMaturity(StockMaturityEnum.M3_SEASONED.getCode());
        batch.setRiskLevel(StockRiskLevelEnum.MEDIUM.getCode());
        batch.setQuantity(100L);
        batch.setInvestedCash(new BigDecimal("10000.00"));
        batch.setSlotNo(2);
        batch.setFollowUntil(LocalDateTime.of(2026, 7, 25, 10, 0));
        batch.setFollowMaxPrice(new BigDecimal("100.15"));
        return batch;
    }

    /**
     * 构建卖出批次测试数据(已平仓状态)。
     *
     * @param closeTypeCode 关闭类型编码(如 CLOSED_TARGET / CLOSED_RISK)
     * @param netReturn     净收益率(小数形式,如0.008表示+0.8%)
     * @return 预设字段的卖出批次DO
     */
    private static TornStockVirtualBatchDO buildSellBatch(String closeTypeCode, BigDecimal netReturn) {
        TornStockVirtualBatchDO batch = buildBuyBatch();
        batch.setExitReferencePrice(new BigDecimal("100.80"));
        batch.setExitTime(LocalDateTime.of(2026, 7, 25, 12, 0));
        batch.setEntryTime(batch.getExitTime().minusDays(3).minusHours(5));
        batch.setExitReason(closeTypeCode);
        batch.setNetReturn(netReturn);
        batch.setSellProceeds(new BigDecimal("10080.00"));
        return batch;
    }

    /**
     * 构建通知审计测试数据。
     *
     * @param id         通知ID
     * @param batchId    关联批次ID
     * @param noticeType 通知类型编码(BUY/SELL)
     * @return 预设字段的通知审计DO
     */
    private static TornStockNoticeAuditDO buildNotice(Long id, Long batchId, String noticeType) {
        TornStockNoticeAuditDO notice = new TornStockNoticeAuditDO();
        notice.setId(id);
        notice.setBatchId(batchId);
        notice.setNoticeType(noticeType);
        notice.setScheduledRoundTime(LocalDateTime.of(2026, 7, 25, 12, 0));
        return notice;
    }
}
