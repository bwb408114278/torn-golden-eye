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
 * 证明第六批Review {@code R-ALPHA-PRE-001} 要求的组级状态闭包数据库级语义:
 * <ul>
 *   <li>组级领取后释放:两腿{@code send_status}与{@code rebalance_group_status}同步写入同一结果,
 *       尝试次数不被归零,释放后下一轮能够再次以完整两腿领取</li>
 *   <li>组级租约恢复:同一次租约的完整两腿同步恢复为同一组状态,{@code claim_time == staleBefore}不恢复,
 *       恢复后未归零尝试次数且可重试组能够再次完整领取</li>
 *   <li>组级领取只接受恰好两腿、尝试次数相同且组状态按兼容口径一致的完整组</li>
 *   <li>异常收敛按当前领取标识所有权执行:其他领取标识或存在其他持有者时更新0行,
 *       组内状态、组状态与领取标识完全不变</li>
 *   <li>当前流程部分写为{@code SENT}时收敛只更新仍由本流程持有的腿,不降级已成功腿</li>
 *   <li>完整两腿在同一领取标识下组级成功回写一次更新2行,任一腿不满足条件时更新0行</li>
 *   <li>失败回写仅在两腿尝试次数相同时生效,两腿与组状态得到同一失败结果</li>
 *   <li>无持有者收敛把非{@code SENT}腿写为{@code FAILED_FINAL},已确认成功的完整组不被覆盖</li>
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
    @DisplayName("一腿SENT一腿可重试且无持有者_无持有者收敛统一写INCONSISTENT且不降级已成功腿")
    void convergeUnclaimedRebalanceGroup_mixedSentAndRetryable_persistsInconsistent() {
        String associationId = newAssociationId();
        Long sentSellLegId = insertLeg(associationId, "SELL", 1, 910041L,
                StockNoticeStatusEnum.SENT.getCode(), 1, "claim-old", null);
        Long retryableBuyLegId = insertLeg(associationId, "BUY", 2, 910042L,
                StockNoticeStatusEnum.FAILED_RETRYABLE.getCode(), 1, "claim-old", null);

        assertEquals(2, noticeAuditDao.convergeUnclaimedRebalanceGroup(associationId,
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
    @DisplayName("已确认成功的完整组_两种异常收敛均不得覆盖")
    void convergeConfirmedSuccessGroup_ownedAndUnclaimedEntries_areNotOverwritten() {
        String associationId = newAssociationId();
        Long sellLegId = insertLeg(associationId, "SELL", 1, 910051L,
                StockNoticeStatusEnum.SENT.getCode(), 1, "claim-done",
                StockNoticeRebalanceGroupStatusEnum.SENT.getCode());
        Long buyLegId = insertLeg(associationId, "BUY", 2, 910052L,
                StockNoticeStatusEnum.SENT.getCode(), 1, "claim-done",
                StockNoticeRebalanceGroupStatusEnum.SENT.getCode());

        assertEquals(0, noticeAuditDao.convergeUnclaimedRebalanceGroup(associationId,
                        StockNoticeRebalanceGroupStatusEnum.INCONSISTENT.getCode(),
                        "禁止覆盖已确认成功组", BUSINESS_NOW),
                "已确认成功的完整组必须被保护,不得被无持有者收敛覆盖");
        assertEquals(0, noticeAuditDao.convergeOwnedRebalanceGroup(associationId, "claim-done",
                        StockNoticeRebalanceGroupStatusEnum.INCONSISTENT.getCode(),
                        "禁止覆盖已确认成功组", BUSINESS_NOW),
                "已确认成功的完整组也必须被所有权收敛保护");

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

    @Test
    @DisplayName("组级领取后释放_两腿send_status与组状态同步且可再次完整领取")
    void releaseClaim_wholeClaimedGroup_syncsBothLegsAndAllowsReclaim() {
        String associationId = newAssociationId();
        Long sellLegId = insertLeg(associationId, "SELL", 1, 910101L,
                StockNoticeStatusEnum.PENDING.getCode(), 0, null, null);
        Long buyLegId = insertLeg(associationId, "BUY", 2, 910102L,
                StockNoticeStatusEnum.PENDING.getCode(), 0, null, null);
        assertEquals(2, noticeAuditDao.claimByRebalanceAssociationId(associationId, "it-release",
                BUSINESS_NOW), "完整两腿必须可被组级领取");

        assertEquals(2, noticeAuditDao.releaseClaim("it-release", BUSINESS_NOW),
                "本领取标识持有完整两腿时必须一次同步释放两腿");

        for (Long legId : List.of(sellLegId, buyLegId)) {
            TornStockNoticeAuditDO leg = noticeAuditDao.getById(legId);
            assertEquals(StockNoticeStatusEnum.FAILED_RETRYABLE.getCode(), leg.getSendStatus());
            assertEquals(StockNoticeRebalanceGroupStatusEnum.FAILED_RETRYABLE.getCode(),
                    leg.getRebalanceGroupStatus(), "释放后组状态必须与腿状态同步,不得停留在SENDING");
            assertEquals(1, leg.getSendAttemptCount(), "释放不得归零尝试次数");
            assertEquals(BUSINESS_NOW, leg.getUpdateTime(), "更新时间必须等于调用方传入的业务时间");
        }

        assertEquals(2, noticeAuditDao.claimByRebalanceAssociationId(associationId, "it-release-2",
                        BUSINESS_NOW),
                "释放后下一轮必须能够再次以完整两腿领取,组状态不得锁死自动重试");
        for (Long legId : List.of(sellLegId, buyLegId)) {
            assertEquals(StockNoticeRebalanceGroupStatusEnum.SENDING.getCode(),
                    noticeAuditDao.getById(legId).getRebalanceGroupStatus(),
                    "重新领取必须再次写入两腿组状态SENDING");
        }
    }

    @Test
    @DisplayName("释放领取_其他领取标识或尝试次数分叉_不产生任何单腿状态变化")
    void releaseClaim_otherTokenOrDivergentAttempts_updatesNothing() {
        String otherTokenAssociation = newAssociationId();
        Long otherSellLegId = insertLeg(otherTokenAssociation, "SELL", 1, 910111L,
                StockNoticeStatusEnum.SENDING.getCode(), 1, "token-owner",
                StockNoticeRebalanceGroupStatusEnum.SENDING.getCode());
        Long otherBuyLegId = insertLeg(otherTokenAssociation, "BUY", 2, 910112L,
                StockNoticeStatusEnum.SENDING.getCode(), 1, "token-owner",
                StockNoticeRebalanceGroupStatusEnum.SENDING.getCode());

        assertEquals(0, noticeAuditDao.releaseClaim("token-contender", BUSINESS_NOW),
                "其他领取标识不得释放本流程未持有的组");
        for (Long legId : List.of(otherSellLegId, otherBuyLegId)) {
            TornStockNoticeAuditDO leg = noticeAuditDao.getById(legId);
            assertEquals(StockNoticeStatusEnum.SENDING.getCode(), leg.getSendStatus());
            assertEquals(StockNoticeRebalanceGroupStatusEnum.SENDING.getCode(), leg.getRebalanceGroupStatus());
            assertEquals("token-owner", leg.getClaimToken(), "其他流程的领取标识不得被替换");
        }

        String divergedAssociation = newAssociationId();
        Long divergedSellLegId = insertLeg(divergedAssociation, "SELL", 1, 910113L,
                StockNoticeStatusEnum.SENDING.getCode(), 1, "token-diverged",
                StockNoticeRebalanceGroupStatusEnum.SENDING.getCode());
        Long divergedBuyLegId = insertLeg(divergedAssociation, "BUY", 2, 910114L,
                StockNoticeStatusEnum.SENDING.getCode(), 2, "token-diverged",
                StockNoticeRebalanceGroupStatusEnum.SENDING.getCode());

        assertEquals(0, noticeAuditDao.releaseClaim("token-diverged", BUSINESS_NOW),
                "尝试次数分叉时不得猜测重试次数,组分支必须更新0行");
        for (Long legId : List.of(divergedSellLegId, divergedBuyLegId)) {
            TornStockNoticeAuditDO leg = noticeAuditDao.getById(legId);
            assertEquals(StockNoticeStatusEnum.SENDING.getCode(), leg.getSendStatus(),
                    "组分支不满足条件时不得只更新其中一腿");
            assertEquals(StockNoticeRebalanceGroupStatusEnum.SENDING.getCode(), leg.getRebalanceGroupStatus());
        }
    }

    @Test
    @DisplayName("组级租约恢复_同一租约完整两腿同步恢复且边界与分叉不恢复")
    void recoverStaleClaims_wholeClaimedGroups_syncGroupStatusWithStrictBoundary() {
        LocalDateTime staleBoundary = BUSINESS_NOW.minusMinutes(5);
        LocalDateTime staleClaimAt = BUSINESS_NOW.minusMinutes(30);

        String retryAssociation = newAssociationId();
        Long retrySellLegId = insertLeg(retryAssociation, "SELL", 1, 910121L,
                StockNoticeStatusEnum.PENDING.getCode(), 0, null, null);
        Long retryBuyLegId = insertLeg(retryAssociation, "BUY", 2, 910122L,
                StockNoticeStatusEnum.PENDING.getCode(), 0, null, null);
        assertEquals(2, noticeAuditDao.claimByRebalanceAssociationId(retryAssociation, "it-stale-retry",
                staleClaimAt), "夹具组必须通过真实组级领取进入同一次租约");

        String finalAssociation = newAssociationId();
        Long finalSellLegId = insertLeg(finalAssociation, "SELL", 1, 910123L,
                StockNoticeStatusEnum.PENDING.getCode(), 2, null, null);
        Long finalBuyLegId = insertLeg(finalAssociation, "BUY", 2, 910124L,
                StockNoticeStatusEnum.PENDING.getCode(), 2, null, null);
        assertEquals(2, noticeAuditDao.claimByRebalanceAssociationId(finalAssociation, "it-stale-final",
                staleClaimAt));

        String boundaryAssociation = newAssociationId();
        Long boundarySellLegId = insertLeg(boundaryAssociation, "SELL", 1, 910125L,
                StockNoticeStatusEnum.PENDING.getCode(), 0, null, null);
        Long boundaryBuyLegId = insertLeg(boundaryAssociation, "BUY", 2, 910126L,
                StockNoticeStatusEnum.PENDING.getCode(), 0, null, null);
        assertEquals(2, noticeAuditDao.claimByRebalanceAssociationId(boundaryAssociation, "it-stale-boundary",
                staleBoundary), "边界夹具的领取时间必须恰好等于租约截止时间");

        assertTrue(noticeAuditDao.recoverStaleClaims(staleBoundary, BUSINESS_NOW) >= 2,
                "同一租约的完整超时组必须被恢复");

        for (Long legId : List.of(retrySellLegId, retryBuyLegId)) {
            TornStockNoticeAuditDO leg = noticeAuditDao.getById(legId);
            assertEquals(StockNoticeStatusEnum.FAILED_RETRYABLE.getCode(), leg.getSendStatus());
            assertEquals(StockNoticeRebalanceGroupStatusEnum.FAILED_RETRYABLE.getCode(),
                    leg.getRebalanceGroupStatus(), "未达上限时组状态必须与腿状态同步恢复");
            assertEquals(1, leg.getSendAttemptCount(), "恢复不得归零尝试次数");
            assertEquals(BUSINESS_NOW, leg.getUpdateTime());
        }
        for (Long legId : List.of(finalSellLegId, finalBuyLegId)) {
            TornStockNoticeAuditDO leg = noticeAuditDao.getById(legId);
            assertEquals(StockNoticeStatusEnum.FAILED_FINAL.getCode(), leg.getSendStatus());
            assertEquals(StockNoticeRebalanceGroupStatusEnum.FAILED_FINAL.getCode(), leg.getRebalanceGroupStatus(),
                    "达到上限时两腿组状态必须同步进入最终失败");
            assertEquals(3, leg.getSendAttemptCount());
        }
        for (Long legId : List.of(boundarySellLegId, boundaryBuyLegId)) {
            TornStockNoticeAuditDO leg = noticeAuditDao.getById(legId);
            assertEquals(StockNoticeStatusEnum.SENDING.getCode(), leg.getSendStatus(),
                    "claim_time == staleBefore 不得恢复");
            assertEquals(StockNoticeRebalanceGroupStatusEnum.SENDING.getCode(), leg.getRebalanceGroupStatus());
        }

        assertEquals(2, noticeAuditDao.claimByRebalanceAssociationId(retryAssociation, "it-stale-retry-2",
                        BUSINESS_NOW),
                "租约恢复后的组必须能够再次以完整两腿领取");
    }

    @Test
    @DisplayName("异常收敛所有权_错误领取标识与存在其他持有者时均不写入任何行")
    void converge_ownershipBoundary_neverOverwritesOtherClaimant() {
        String associationId = newAssociationId();
        Long sellLegId = insertLeg(associationId, "SELL", 1, 910131L,
                StockNoticeStatusEnum.SENDING.getCode(), 1, "token-a",
                StockNoticeRebalanceGroupStatusEnum.SENDING.getCode());
        Long buyLegId = insertLeg(associationId, "BUY", 2, 910132L,
                StockNoticeStatusEnum.SENDING.getCode(), 1, "token-a",
                StockNoticeRebalanceGroupStatusEnum.SENDING.getCode());

        assertEquals(0, noticeAuditDao.convergeOwnedRebalanceGroup(associationId, "token-b",
                        StockNoticeRebalanceGroupStatusEnum.INCONSISTENT.getCode(),
                        "竞争流程禁止覆盖", BUSINESS_NOW),
                "没有当前claimToken的所有权证明时不得覆盖SENDING行");
        assertEquals(0, noticeAuditDao.convergeUnclaimedRebalanceGroup(associationId,
                        StockNoticeRebalanceGroupStatusEnum.INCONSISTENT.getCode(),
                        "存在持有者时禁止无持有者收敛", BUSINESS_NOW),
                "组内存在SENDING持有者时无持有者收敛必须更新0行");
        for (Long legId : List.of(sellLegId, buyLegId)) {
            TornStockNoticeAuditDO leg = noticeAuditDao.getById(legId);
            assertEquals(StockNoticeStatusEnum.SENDING.getCode(), leg.getSendStatus());
            assertEquals(StockNoticeRebalanceGroupStatusEnum.SENDING.getCode(), leg.getRebalanceGroupStatus());
            assertEquals("token-a", leg.getClaimToken(), "其他流程的领取标识不得被替换");
            assertNull(leg.getRebalanceGroupError(), "未写入时不得留下组级异常原因");
        }

        assertEquals(2, noticeAuditDao.convergeOwnedRebalanceGroup(associationId, "token-a",
                        StockNoticeRebalanceGroupStatusEnum.INCONSISTENT.getCode(),
                        "结果未知人工核验", BUSINESS_NOW),
                "所有权仍可证明时必须允许按当前claimToken收敛");
        for (Long legId : List.of(sellLegId, buyLegId)) {
            TornStockNoticeAuditDO leg = noticeAuditDao.getById(legId);
            assertEquals(StockNoticeStatusEnum.FAILED_FINAL.getCode(), leg.getSendStatus());
            assertEquals(StockNoticeRebalanceGroupStatusEnum.INCONSISTENT.getCode(), leg.getRebalanceGroupStatus());
            assertEquals("结果未知人工核验", leg.getRebalanceGroupError());
        }
    }

    @Test
    @DisplayName("当前流程部分写为SENT_所有权收敛保留SENT腿且不降级")
    void convergeOwnedRebalanceGroup_partialSentLeg_keepsSentLeg() {
        String associationId = newAssociationId();
        Long sentLegId = insertLeg(associationId, "SELL", 1, 910141L,
                StockNoticeStatusEnum.SENT.getCode(), 1, "token-owned",
                StockNoticeRebalanceGroupStatusEnum.SENDING.getCode());
        Long sendingLegId = insertLeg(associationId, "BUY", 2, 910142L,
                StockNoticeStatusEnum.SENDING.getCode(), 1, "token-owned",
                StockNoticeRebalanceGroupStatusEnum.SENDING.getCode());

        assertEquals(2, noticeAuditDao.convergeOwnedRebalanceGroup(associationId, "token-owned",
                        StockNoticeRebalanceGroupStatusEnum.INCONSISTENT.getCode(),
                        "一腿已SENT另一腿仍由本流程持有", BUSINESS_NOW),
                "一腿已由本流程写为SENT时仍必须能够收敛组状态");

        assertEquals(StockNoticeStatusEnum.SENT.getCode(), noticeAuditDao.getById(sentLegId).getSendStatus(),
                "已确认发送成功的腿不得被降级");
        assertEquals(StockNoticeRebalanceGroupStatusEnum.INCONSISTENT.getCode(),
                noticeAuditDao.getById(sentLegId).getRebalanceGroupStatus());
        assertEquals(StockNoticeStatusEnum.FAILED_FINAL.getCode(),
                noticeAuditDao.getById(sendingLegId).getSendStatus());
    }

    @Test
    @DisplayName("组级领取前置条件_尝试次数或组状态分叉时更新0行且NULL组状态按PENDING兼容")
    void claimByRebalanceAssociationId_divergentGroupFacts_updatesNothing() {
        String attemptsAssociation = newAssociationId();
        Long attemptsSellLegId = insertLeg(attemptsAssociation, "SELL", 1, 910151L,
                StockNoticeStatusEnum.FAILED_RETRYABLE.getCode(), 1, null,
                StockNoticeRebalanceGroupStatusEnum.FAILED_RETRYABLE.getCode());
        Long attemptsBuyLegId = insertLeg(attemptsAssociation, "BUY", 2, 910152L,
                StockNoticeStatusEnum.FAILED_RETRYABLE.getCode(), 2, null,
                StockNoticeRebalanceGroupStatusEnum.FAILED_RETRYABLE.getCode());

        assertEquals(0, noticeAuditDao.claimByRebalanceAssociationId(attemptsAssociation, "it-claim-diverged",
                        BUSINESS_NOW),
                "两腿尝试次数不同的组不得进入发送流程");
        assertEquals(StockNoticeStatusEnum.FAILED_RETRYABLE.getCode(),
                noticeAuditDao.getById(attemptsSellLegId).getSendStatus());
        assertEquals(StockNoticeStatusEnum.FAILED_RETRYABLE.getCode(),
                noticeAuditDao.getById(attemptsBuyLegId).getSendStatus());

        String statusAssociation = newAssociationId();
        insertLeg(statusAssociation, "SELL", 1, 910153L, StockNoticeStatusEnum.PENDING.getCode(), 0, null, null);
        insertLeg(statusAssociation, "BUY", 2, 910154L, StockNoticeStatusEnum.PENDING.getCode(), 0, null,
                StockNoticeRebalanceGroupStatusEnum.FAILED_RETRYABLE.getCode());
        assertEquals(0, noticeAuditDao.claimByRebalanceAssociationId(statusAssociation, "it-claim-status",
                        BUSINESS_NOW),
                "两腿组状态不一致的组不得进入发送流程");

        String nullStatusAssociation = newAssociationId();
        Long nullStatusSellLegId = insertLeg(nullStatusAssociation, "SELL", 1, 910155L,
                StockNoticeStatusEnum.PENDING.getCode(), 0, null, null);
        Long nullStatusBuyLegId = insertLeg(nullStatusAssociation, "BUY", 2, 910156L,
                StockNoticeStatusEnum.PENDING.getCode(), 0, null, null);
        assertEquals(2, noticeAuditDao.claimByRebalanceAssociationId(nullStatusAssociation, "it-claim-null",
                        BUSINESS_NOW),
                "两腿组状态均为NULL必须按历史兼容口径视为PENDING并允许领取");
        assertEquals(StockNoticeRebalanceGroupStatusEnum.SENDING.getCode(),
                noticeAuditDao.getById(nullStatusSellLegId).getRebalanceGroupStatus());
        assertEquals(StockNoticeRebalanceGroupStatusEnum.SENDING.getCode(),
                noticeAuditDao.getById(nullStatusBuyLegId).getRebalanceGroupStatus());
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
