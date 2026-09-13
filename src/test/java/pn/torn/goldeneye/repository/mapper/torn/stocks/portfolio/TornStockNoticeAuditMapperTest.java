package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeRebalanceGroupStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeTypeEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticePayloadCanonicalizer;
import pn.torn.goldeneye.utils.image.render.html.PlaywrightBrowserManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 股票通知α换仓关联组真实PostgreSQL原子回写测试。
 * <p>
 * 证明第五批Review {@code R-ALPHA-PRE-001} 要求的数据库级语义:
 * <ul>
 *   <li>完整两腿在同一领取标识下组级成功回写一次更新2行,两腿读回均为{@code SENT}且组状态{@code SENT}</li>
 *   <li>任一腿不满足条件时组级回写更新0行,且不产生任何单腿终态</li>
 *   <li>失败回写仅在两腿尝试次数相同时生效,两腿与组状态得到同一失败结果</li>
 *   <li>组异常收敛把非{@code SENT}腿写为{@code FAILED_FINAL},已确认成功的腿不被降级,组状态统一为{@code INCONSISTENT}</li>
 *   <li>已确认成功的完整组不会被异常收敛覆盖</li>
 *   <li>普通通知(无组状态)仍可被可发送查询读取,新增列对既有通知兼容</li>
 * </ul>
 * 全部读写通过真实DAO完成,测试方法内不写SQL;夹具为随机关联标识,由{@code @Rollback}整体回滚,
 * 不使用{@code setval}、手工ID、逻辑删除冒充清理或{@code @Disabled}。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.13
 */
@SpringBootTest
@Transactional
@Rollback
@Tag("shared-db")
@DisplayName("股票通知α换仓关联组真实数据库原子回写测试")
class TornStockNoticeAuditMapperTest {

    /**
     * 夹具统一业务时间。
     */
    private static final LocalDateTime BUSINESS_NOW = LocalDateTime.of(2026, 9, 13, 11, 0);
    /**
     * 夹具消息规则版本。
     */
    private static final String MESSAGE_RULE_VERSION = "IT-1.6.1";

    @Autowired
    private TornStockNoticeAuditDAO noticeAuditDao;
    /**
     * 表格图片渲染浏览器与本组级原子回写证据无关,且需要下载/启动外部Chromium;测试上下文以替身替换,
     * 不改变真实通知审计DAO与真实组级SQL。
     */
    @MockitoBean
    private PlaywrightBrowserManager playwrightBrowserManager;

    @Test
    @DisplayName("完整两腿同一领取标识_组级成功回写一次更新2行且组状态与两腿状态可读回")
    void markRebalanceGroupSent_wholeGroup_updatesBothLegsAtomically() {
        String associationId = newAssociationId();
        Long sellLegId = insertLeg(associationId, "SELL", 1, 910001L,
                StockNoticeStatusEnum.SENDING.getCode(), 1, "claim-group",
                StockNoticeRebalanceGroupStatusEnum.SENDING.getCode());
        Long buyLegId = insertLeg(associationId, "BUY", 2, 910002L,
                StockNoticeStatusEnum.SENDING.getCode(), 1, "claim-group",
                StockNoticeRebalanceGroupStatusEnum.SENDING.getCode());

        assertEquals(2, noticeAuditDao.markRebalanceGroupSent(associationId, "claim-group", BUSINESS_NOW),
                "完整两腿必须一次组级原子回写2行");

        for (Long legId : List.of(sellLegId, buyLegId)) {
            TornStockNoticeAuditDO leg = noticeAuditDao.getById(legId);
            assertEquals(StockNoticeStatusEnum.SENT.getCode(), leg.getSendStatus());
            assertEquals(StockNoticeRebalanceGroupStatusEnum.SENT.getCode(), leg.getRebalanceGroupStatus(),
                    "两腿组状态必须读回一致");
            assertEquals(BUSINESS_NOW, leg.getSentAt(), "成功时间必须等于调用方传入的业务时间");
            assertEquals(BUSINESS_NOW, leg.getUpdateTime(), "更新时间必须等于调用方传入的业务时间");
        }
    }

    @Test
    @DisplayName("任一腿领取标识不匹配_组级成功回写更新0行且不产生单腿终态")
    void markRebalanceGroupSent_partialCondition_updatesNothing() {
        String associationId = newAssociationId();
        Long sellLegId = insertLeg(associationId, "SELL", 1, 910011L,
                StockNoticeStatusEnum.SENDING.getCode(), 1, "claim-a",
                StockNoticeRebalanceGroupStatusEnum.SENDING.getCode());
        Long buyLegId = insertLeg(associationId, "BUY", 2, 910012L,
                StockNoticeStatusEnum.SENDING.getCode(), 1, "claim-b",
                StockNoticeRebalanceGroupStatusEnum.SENDING.getCode());

        assertEquals(0, noticeAuditDao.markRebalanceGroupSent(associationId, "claim-a", BUSINESS_NOW),
                "只有部分腿满足条件时必须以更新0行fail-closed");

        for (Long legId : List.of(sellLegId, buyLegId)) {
            TornStockNoticeAuditDO leg = noticeAuditDao.getById(legId);
            assertEquals(StockNoticeStatusEnum.SENDING.getCode(), leg.getSendStatus(),
                    "组级回写失败时不得产生任何单腿终态");
            assertEquals(StockNoticeRebalanceGroupStatusEnum.SENDING.getCode(), leg.getRebalanceGroupStatus());
            assertNull(leg.getSentAt(), "组级回写失败时不得写入发送成功时间");
        }
    }

    @Test
    @DisplayName("两腿尝试次数相同_组级失败回写两腿与组状态得到同一失败结果")
    void markRebalanceGroupFailed_equalAttempts_writesSameGroupResult() {
        String associationId = newAssociationId();
        Long sellLegId = insertLeg(associationId, "SELL", 1, 910021L,
                StockNoticeStatusEnum.SENDING.getCode(), 1, "claim-fail",
                StockNoticeRebalanceGroupStatusEnum.SENDING.getCode());
        Long buyLegId = insertLeg(associationId, "BUY", 2, 910022L,
                StockNoticeStatusEnum.SENDING.getCode(), 1, "claim-fail",
                StockNoticeRebalanceGroupStatusEnum.SENDING.getCode());

        assertEquals(2, noticeAuditDao.markRebalanceGroupFailed(associationId, "claim-fail",
                        "模拟Bot发送失败", BUSINESS_NOW),
                "尝试次数相同的完整两腿必须一次组级原子回写2行");

        for (Long legId : List.of(sellLegId, buyLegId)) {
            TornStockNoticeAuditDO leg = noticeAuditDao.getById(legId);
            assertEquals(StockNoticeStatusEnum.FAILED_RETRYABLE.getCode(), leg.getSendStatus());
            assertEquals(StockNoticeRebalanceGroupStatusEnum.FAILED_RETRYABLE.getCode(),
                    leg.getRebalanceGroupStatus(), "两腿组状态必须得到同一失败结果");
            assertEquals("模拟Bot发送失败", leg.getErrorMessage());
            assertEquals("模拟Bot发送失败", leg.getRebalanceGroupError());
        }
    }

    @Test
    @DisplayName("两腿尝试次数分叉_组级失败回写更新0行且不猜测重试次数")
    void markRebalanceGroupFailed_divergentAttempts_updatesNothing() {
        String associationId = newAssociationId();
        Long sellLegId = insertLeg(associationId, "SELL", 1, 910031L,
                StockNoticeStatusEnum.SENDING.getCode(), 1, "claim-diverge",
                StockNoticeRebalanceGroupStatusEnum.SENDING.getCode());
        Long buyLegId = insertLeg(associationId, "BUY", 2, 910032L,
                StockNoticeStatusEnum.SENDING.getCode(), 2, "claim-diverge",
                StockNoticeRebalanceGroupStatusEnum.SENDING.getCode());

        assertEquals(0, noticeAuditDao.markRebalanceGroupFailed(associationId, "claim-diverge",
                        "组事实不一致", BUSINESS_NOW),
                "两腿尝试次数不同说明组事实已分叉,必须以更新0行fail-closed");

        assertEquals(StockNoticeStatusEnum.SENDING.getCode(),
                noticeAuditDao.getById(sellLegId).getSendStatus());
        assertEquals(StockNoticeStatusEnum.SENDING.getCode(),
                noticeAuditDao.getById(buyLegId).getSendStatus());
        assertNull(noticeAuditDao.getById(buyLegId).getRebalanceGroupError(),
                "组级失败回写未生效时不得只写单腿原因");
    }

    @Test
    @DisplayName("一腿SENT一腿可重试_异常收敛统一写INCONSISTENT且不降级已成功腿")
    void convergeRebalanceGroup_mixedSentAndRetryable_persistsInconsistent() {
        String associationId = newAssociationId();
        Long sentSellLegId = insertLeg(associationId, "SELL", 1, 910041L,
                StockNoticeStatusEnum.SENT.getCode(), 1, "claim-old", null);
        Long retryableBuyLegId = insertLeg(associationId, "BUY", 2, 910042L,
                StockNoticeStatusEnum.FAILED_RETRYABLE.getCode(), 1, "claim-old", null);

        assertEquals(2, noticeAuditDao.convergeRebalanceGroup(associationId,
                        StockNoticeRebalanceGroupStatusEnum.INCONSISTENT.getCode(),
                        "一腿SENT一腿可重试", BUSINESS_NOW),
                "缺腿以外的组异常必须覆盖关联组内全部腿");

        TornStockNoticeAuditDO sentLeg = noticeAuditDao.getById(sentSellLegId);
        assertEquals(StockNoticeStatusEnum.SENT.getCode(), sentLeg.getSendStatus(),
                "已确认发送成功的腿不得被异常收敛降级");
        assertEquals(StockNoticeRebalanceGroupStatusEnum.INCONSISTENT.getCode(),
                sentLeg.getRebalanceGroupStatus());
        assertEquals("一腿SENT一腿可重试", sentLeg.getRebalanceGroupError());

        TornStockNoticeAuditDO retryableLeg = noticeAuditDao.getById(retryableBuyLegId);
        assertEquals(StockNoticeStatusEnum.FAILED_FINAL.getCode(), retryableLeg.getSendStatus(),
                "非SENT腿必须进入人工核验终态");
        assertEquals(StockNoticeRebalanceGroupStatusEnum.INCONSISTENT.getCode(),
                retryableLeg.getRebalanceGroupStatus());
    }

    @Test
    @DisplayName("已确认成功的完整组_异常收敛不得覆盖")
    void convergeRebalanceGroup_confirmedSuccessGroup_isNotOverwritten() {
        String associationId = newAssociationId();
        Long sellLegId = insertLeg(associationId, "SELL", 1, 910051L,
                StockNoticeStatusEnum.SENT.getCode(), 1, "claim-done",
                StockNoticeRebalanceGroupStatusEnum.SENT.getCode());
        Long buyLegId = insertLeg(associationId, "BUY", 2, 910052L,
                StockNoticeStatusEnum.SENT.getCode(), 1, "claim-done",
                StockNoticeRebalanceGroupStatusEnum.SENT.getCode());

        assertEquals(0, noticeAuditDao.convergeRebalanceGroup(associationId,
                        StockNoticeRebalanceGroupStatusEnum.INCONSISTENT.getCode(),
                        "禁止覆盖已确认成功组", BUSINESS_NOW),
                "已确认成功的完整组必须被保护,不得被异常收敛覆盖");

        for (Long legId : List.of(sellLegId, buyLegId)) {
            TornStockNoticeAuditDO leg = noticeAuditDao.getById(legId);
            assertEquals(StockNoticeStatusEnum.SENT.getCode(), leg.getSendStatus());
            assertEquals(StockNoticeRebalanceGroupStatusEnum.SENT.getCode(), leg.getRebalanceGroupStatus());
            assertNull(leg.getRebalanceGroupError(), "已确认成功组不得被写入组级异常原因");
        }
    }

    @Test
    @DisplayName("既有普通通知_无组状态时仍可被可发送查询读取")
    void ordinaryNotice_withoutGroupStatus_remainsSendable() {
        TornStockNoticeAuditDO summary = new TornStockNoticeAuditDO();
        summary.setNoticeNo("ITM" + UUID.randomUUID());
        summary.setNoticeType(StockNoticeTypeEnum.DAILY_SUMMARY.getCode());
        // 每日摘要受ck_notice_summary_date约束:摘要类通知必须携带摘要日期
        summary.setSummaryDate(LocalDate.of(2099, 12, 31));
        summary.setGroupId(909796613L);
        summary.setPayloadSnapshot("{\"noticeType\":\"DAILY_SUMMARY\","
                + "\"messageText\":\"兼容性读回夹具\"}");
        summary.setPayloadHash(StockNoticePayloadCanonicalizer.sha256(summary.getPayloadSnapshot()));
        summary.setSendStatus(StockNoticeStatusEnum.PENDING.getCode());
        summary.setSendAttemptCount(0);
        summary.setMessageRuleVersion(MESSAGE_RULE_VERSION);
        assertTrue(noticeAuditDao.save(summary), "夹具通知必须写入成功");

        TornStockNoticeAuditDO persisted = noticeAuditDao.getById(summary.getId());
        assertNull(persisted.getRebalanceGroupStatus(), "普通通知组状态必须保持null");
        assertNull(persisted.getRebalanceGroupError(), "普通通知组级原因必须保持null");
        assertTrue(noticeAuditDao.selectSendableNotices().stream()
                        .anyMatch(notice -> summary.getId().equals(notice.getId())),
                "新增组状态列不得影响既有普通通知的可发送读取");
    }

    /**
     * 生成随机α换仓关联标识,避免与生产数据或其他测试冲突。
     *
     * @return 换仓关联标识
     */
    private String newAssociationId() {
        return "ALPHA_REBALANCE:ITM-" + UUID.randomUUID();
    }

    /**
     * 写入一条α换仓腿夹具通知。
     *
     * @param associationId 换仓关联标识
     * @param leg           腿标识(SELL/BUY)
     * @param legOrder      腿顺序
     * @param batchId       关联批次ID
     * @param sendStatus    发送状态
     * @param attemptCount  发送尝试次数
     * @param claimToken    领取标识
     * @param groupStatus   组级状态;null表示未写入
     * @return 通知主键
     */
    private Long insertLeg(String associationId, String leg, int legOrder, Long batchId,
                           String sendStatus, int attemptCount, String claimToken, String groupStatus) {
        String payload = "{\"noticeType\":\"ALPHA_REBALANCE\",\"batchId\":" + batchId
                + ",\"rebalanceDecisionId\":1"
                + ",\"rebalanceAssociationId\":\"" + associationId + "\""
                + ",\"originalBatchId\":910001,\"replacementBatchId\":910002"
                + ",\"rebalanceLeg\":\"" + leg + "\",\"legOrder\":" + legOrder + "}";
        TornStockNoticeAuditDO notice = new TornStockNoticeAuditDO();
        notice.setNoticeNo("ITM" + UUID.randomUUID());
        notice.setNoticeType(StockNoticeTypeEnum.ALPHA_REBALANCE.getCode());
        notice.setBatchId(batchId);
        notice.setGroupId(909796613L);
        notice.setPayloadSnapshot(payload);
        notice.setPayloadHash(StockNoticePayloadCanonicalizer.sha256(payload));
        notice.setSendStatus(sendStatus);
        notice.setSendAttemptCount(attemptCount);
        notice.setClaimToken(claimToken);
        notice.setClaimTime(BUSINESS_NOW);
        notice.setRebalanceGroupStatus(groupStatus);
        notice.setMessageRuleVersion(MESSAGE_RULE_VERSION);
        assertTrue(noticeAuditDao.save(notice), "夹具通知必须写入成功");
        assertNotNull(notice.getId(), "夹具通知必须回填主键");
        return notice.getId();
    }
}
