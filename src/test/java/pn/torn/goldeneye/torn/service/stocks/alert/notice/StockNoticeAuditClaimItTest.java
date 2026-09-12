package pn.torn.goldeneye.torn.service.stocks.alert.notice;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeTypeEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.utils.image.render.html.PlaywrightBrowserManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 股票通知领取与自动重发的真实PostgreSQL集成测试。
 * <p>
 * 证明第四批Review {@code R-ALPHA-010}/{@code R-ALPHA-011} 要求的数据库级语义:
 * <ul>
 *   <li>并发发送流程处理同一通知时只允许一次领取成功(条件UPDATE即数据库互斥),失败方不得回写终态</li>
 *   <li>回写必须绑定领取标识,旧领取者不能覆盖新状态</li>
 *   <li>失败未达3次总尝试上限进入FAILED_RETRYABLE可被后续调度领取,达到上限进入FAILED_FINAL不再领取</li>
 *   <li>领取租约超时按结果未知恢复为可重发或最终失败,重启不归零尝试次数</li>
 *   <li>α换仓关联组以关联标识为单位原子领取,已SENT腿不会被重新领取</li>
 * </ul>
 * 全部读写通过真实DAO完成,测试文件内不写SQL;夹具为隔离的远期日期与随机编号,结束后逻辑删除。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.12
 */
@SpringBootTest
@Transactional
@Rollback
@Tag("shared-db")
@DisplayName("股票通知领取与自动重发真实数据库测试")
class StockNoticeAuditClaimItTest {

    /**
     * 并发领取的发送流程数。
     */
    private static final int SENDER_COUNT = 2;
    /**
     * 夹具摘要日期(远期隔离,避免与生产数据冲突)。
     */
    private static final LocalDate SUMMARY_DATE_CONCURRENT = LocalDate.of(2099, 12, 1);
    private static final LocalDate SUMMARY_DATE_RETRY = LocalDate.of(2099, 12, 2);
    private static final LocalDate SUMMARY_DATE_STALE_RETRYABLE = LocalDate.of(2099, 12, 3);
    private static final LocalDate SUMMARY_DATE_STALE_FINAL = LocalDate.of(2099, 12, 4);
    /**
     * 已过期的领取时间,用于租约超时恢复。
     */
    private static final LocalDateTime STALE_CLAIM_TIME = LocalDateTime.of(2000, 1, 1, 0, 0);
    /**
     * 通知规则版本(夹具值)。
     */
    private static final String MESSAGE_RULE_VERSION = "IT-1.6.1";

    @Autowired
    private TornStockNoticeAuditDAO noticeAuditDao;
    /**
     * 表格图片渲染浏览器与本领取/重发证据无关,且需要下载/启动外部Chromium;测试上下文以替身替换,
     * 不改变真实通知审计DAO与真实领取SQL。
     */
    @MockitoBean
    private PlaywrightBrowserManager playwrightBrowserManager;

    /**
     * 夹具通知主键,测试结束后逻辑删除。
     */
    private final List<Long> createdNoticeIds = new ArrayList<>();

    @AfterEach
    void removeFixtures() {
        createdNoticeIds.forEach(noticeAuditDao::removeById);
        createdNoticeIds.clear();
    }

    @Test
    @DisplayName("并发发送流程处理同一通知_只允许一次领取且旧领取者不能回写终态")
    void concurrentClaim_onlyOneSenderWins() throws Exception {
        Long noticeId = insertDailySummaryNotice(SUMMARY_DATE_CONCURRENT);
        List<String> claimTokens = List.of("it-claim-a", "it-claim-b");

        List<Integer> claimedRows = claimConcurrently(noticeId, claimTokens);

        assertEquals(1, claimedRows.get(0) + claimedRows.get(1),
                "两个并发发送流程只允许一次领取成功,未领取成功者不得调用Bot");

        TornStockNoticeAuditDO claimed = noticeAuditDao.getById(noticeId);
        assertEquals(StockNoticeStatusEnum.SENDING.getCode(), claimed.getSendStatus());
        assertEquals(1, claimed.getSendAttemptCount(), "领取即累计一次发送尝试");
        assertNotNull(claimed.getClaimTime(), "领取必须写入领取时间");

        String winnerToken = claimed.getClaimToken();
        String loserToken = winnerToken.equals(claimTokens.get(0)) ? claimTokens.get(1) : claimTokens.get(0);

        // 旧领取者不能覆盖新状态:状态与领取标识必须同时匹配
        assertEquals(0, noticeAuditDao.markSentByIds(List.of(noticeId), loserToken));
        assertEquals(0, noticeAuditDao.markSendFailedByIds(List.of(noticeId), loserToken, "越权回写"));
        assertEquals(StockNoticeStatusEnum.SENDING.getCode(), noticeAuditDao.getById(noticeId).getSendStatus());

        // 本领取者可以回写终态
        assertEquals(1, noticeAuditDao.markSentByIds(List.of(noticeId), winnerToken));
        assertEquals(StockNoticeStatusEnum.SENT.getCode(), noticeAuditDao.getById(noticeId).getSendStatus());
    }

    @Test
    @DisplayName("失败自动重发_总尝试3次后进入FAILED_FINAL且不再被领取")
    void retryLifecycle_threeAttemptsThenFinal() {
        Long noticeId = insertDailySummaryNotice(SUMMARY_DATE_RETRY);

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertTrue(isSendable(noticeId), "第" + attempt + "次发送前必须仍在可发送集合中");
            String claimToken = "it-retry-" + attempt;
            assertEquals(1, noticeAuditDao.claimByIds(List.of(noticeId), claimToken),
                    "第" + attempt + "次尝试必须可被领取");
            assertEquals(1, noticeAuditDao.markSendFailedByIds(List.of(noticeId), claimToken,
                    "模拟第" + attempt + "次发送失败"));

            TornStockNoticeAuditDO afterFailure = noticeAuditDao.getById(noticeId);
            assertEquals(attempt, afterFailure.getSendAttemptCount(), "发送尝试次数必须持久化累计");
            String expectedStatus = attempt < 3
                    ? StockNoticeStatusEnum.FAILED_RETRYABLE.getCode()
                    : StockNoticeStatusEnum.FAILED_FINAL.getCode();
            assertEquals(expectedStatus, afterFailure.getSendStatus());
        }

        // 达到上限后不再领取也不再出现在可发送集合中
        assertEquals(0, noticeAuditDao.claimByIds(List.of(noticeId), "it-retry-4"));
        assertFalse(isSendable(noticeId), "FAILED_FINAL不得再被后续调度领取");
    }

    @Test
    @DisplayName("领取租约超时_按结果未知恢复为可重发或最终失败")
    void staleClaimRecovery_returnsToRetryableOrFinal() {
        Long retryableId = insertClaimedStaleNotice(SUMMARY_DATE_STALE_RETRYABLE, 1);
        Long finalId = insertClaimedStaleNotice(SUMMARY_DATE_STALE_FINAL, 3);

        int recovered = noticeAuditDao.recoverStaleClaims(LocalDateTime.now().minusMinutes(5));

        assertTrue(recovered >= 2, "超时领取必须被恢复, recovered=" + recovered);
        assertEquals(StockNoticeStatusEnum.FAILED_RETRYABLE.getCode(),
                noticeAuditDao.getById(retryableId).getSendStatus(), "未达上限恢复为可重发");
        assertEquals(StockNoticeStatusEnum.FAILED_FINAL.getCode(),
                noticeAuditDao.getById(finalId).getSendStatus(), "达到上限恢复为最终失败");
        assertEquals(1, noticeAuditDao.getById(retryableId).getSendAttemptCount(), "恢复不得归零尝试次数");
        assertTrue(isSendable(retryableId), "恢复后的通知必须可被后续调度自动重发");
        assertFalse(isSendable(finalId));
    }

    @Test
    @DisplayName("α换仓关联组_以关联标识为单位原子领取且已SENT腿不被重新领取")
    void rebalanceGroupClaim_claimsWholeGroupWithoutResendingSentLeg() {
        String associationId = "ALPHA_REBALANCE:IT-" + UUID.randomUUID();
        Long buyLegId = insertRebalanceLeg(associationId, "BUY", 2, 900002L,
                StockNoticeStatusEnum.PENDING.getCode(), 0);
        // 已SENT腿:不得被重新领取,尝试次数不得再次累计
        Long sentSellLegId = insertRebalanceLeg(associationId, "SELL", 1, 900001L,
                StockNoticeStatusEnum.SENT.getCode(), 1);
        String groupToken = "it-group-" + UUID.randomUUID();

        assertEquals(1, noticeAuditDao.claimByRebalanceAssociationId(associationId, groupToken),
                "关联组领取必须以关联标识为单位且只领取仍可发送的腿");
        assertEquals(StockNoticeStatusEnum.SENT.getCode(), noticeAuditDao.getById(sentSellLegId).getSendStatus(),
                "已SENT腿不得被重新领取");
        assertEquals(1, noticeAuditDao.getById(sentSellLegId).getSendAttemptCount(),
                "已SENT腿的尝试次数不得被关联组领取再次累计");

        TornStockNoticeAuditDO claimedBuyLeg = noticeAuditDao.getById(buyLegId);
        assertEquals(StockNoticeStatusEnum.SENDING.getCode(), claimedBuyLeg.getSendStatus());
        assertEquals(1, claimedBuyLeg.getSendAttemptCount());
        assertEquals(groupToken, claimedBuyLeg.getClaimToken());

        // 关联组内已无可发送腿时不再被任何发送流程领取
        assertEquals(0, noticeAuditDao.claimByRebalanceAssociationId(associationId, "it-group-again"));
        assertEquals(1, noticeAuditDao.markSentByIds(List.of(buyLegId), groupToken));

        // 两条腿均为PENDING时,关联组以一次领取原子覆盖两腿
        String wholeGroupAssociation = "ALPHA_REBALANCE:IT-" + UUID.randomUUID();
        insertRebalanceLeg(wholeGroupAssociation, "SELL", 1, 900003L, StockNoticeStatusEnum.PENDING.getCode(), 0);
        insertRebalanceLeg(wholeGroupAssociation, "BUY", 2, 900004L, StockNoticeStatusEnum.PENDING.getCode(), 0);
        assertEquals(2, noticeAuditDao.claimByRebalanceAssociationId(wholeGroupAssociation, "it-group-whole"),
                "完整关联组必须以一次领取原子覆盖两条腿");
        assertEquals(0, noticeAuditDao.claimByRebalanceAssociationId(wholeGroupAssociation, "it-group-whole-2"),
                "已被领取的关联组不得被第二个发送流程重复领取");
    }

    /**
     * 并发领取同一通知,返回各发送流程的领取行数。
     *
     * @param noticeId    通知ID
     * @param claimTokens 各发送流程的领取标识
     * @return 各发送流程的领取行数
     */
    private List<Integer> claimConcurrently(Long noticeId, List<String> claimTokens) throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(SENDER_COUNT);
        try {
            List<Future<Integer>> futures = new ArrayList<>(claimTokens.size());
            for (String claimToken : claimTokens) {
                futures.add(executor.submit(() -> {
                    startGate.await();
                    return noticeAuditDao.claimByIds(List.of(noticeId), claimToken);
                }));
            }
            startGate.countDown();
            List<Integer> claimedRows = new ArrayList<>(futures.size());
            for (Future<Integer> future : futures) {
                claimedRows.add(future.get(30, TimeUnit.SECONDS));
            }
            return claimedRows;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 判断通知当前是否可被发送流程领取。
     *
     * @param noticeId 通知ID
     * @return 出现在可发送集合中返回true
     */
    private boolean isSendable(Long noticeId) {
        return noticeAuditDao.selectSendableNotices().stream()
                .anyMatch(notice -> noticeId.equals(notice.getId()));
    }

    /**
     * 写入一条PENDING的每日摘要夹具通知。
     *
     * @param summaryDate 摘要日期
     * @return 通知主键
     */
    private Long insertDailySummaryNotice(LocalDate summaryDate) {
        return insertNotice(StockNoticeTypeEnum.DAILY_SUMMARY.getCode(), summaryDate, null,
                "{\"noticeType\":\"DAILY_SUMMARY\",\"summaryDate\":\"" + summaryDate + "\","
                        + "\"messageText\":\"IT摘要夹具\"}",
                StockNoticeStatusEnum.PENDING.getCode(), 0, null, null);
    }

    /**
     * 写入一条处于SENDING且领取时间已过期的夹具通知。
     *
     * @param summaryDate  摘要日期
     * @param attemptCount 已累计的发送尝试次数
     * @return 通知主键
     */
    private Long insertClaimedStaleNotice(LocalDate summaryDate, int attemptCount) {
        return insertNotice(StockNoticeTypeEnum.DAILY_SUMMARY.getCode(), summaryDate, null,
                "{\"noticeType\":\"DAILY_SUMMARY\",\"summaryDate\":\"" + summaryDate + "\","
                        + "\"messageText\":\"IT超时夹具\"}",
                StockNoticeStatusEnum.SENDING.getCode(), attemptCount, "it-stale-token", STALE_CLAIM_TIME);
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
     * @return 通知主键
     */
    private Long insertRebalanceLeg(String associationId, String leg, int legOrder, Long batchId,
                                    String sendStatus, int attemptCount) {
        String payload = "{\"noticeType\":\"ALPHA_REBALANCE\",\"batchId\":" + batchId
                + ",\"rebalanceDecisionId\":1"
                + ",\"rebalanceAssociationId\":\"" + associationId + "\""
                + ",\"originalBatchId\":900001,\"replacementBatchId\":900002"
                + ",\"rebalanceLeg\":\"" + leg + "\",\"legOrder\":" + legOrder + "}";
        return insertNotice(StockNoticeTypeEnum.ALPHA_REBALANCE.getCode(), null, batchId, payload,
                sendStatus, attemptCount, null, null);
    }

    /**
     * 通过真实DAO写入夹具通知。
     *
     * @param noticeType      通知类型
     * @param summaryDate     摘要日期;非每日摘要传null
     * @param batchId         关联批次ID
     * @param payloadSnapshot 载荷快照
     * @param sendStatus      发送状态
     * @param attemptCount    发送尝试次数
     * @param claimToken      领取标识
     * @param claimTime       领取时间
     * @return 通知主键
     */
    private Long insertNotice(String noticeType, LocalDate summaryDate, Long batchId, String payloadSnapshot,
                              String sendStatus, int attemptCount, String claimToken, LocalDateTime claimTime) {
        TornStockNoticeAuditDO notice = new TornStockNoticeAuditDO();
        notice.setNoticeNo("IT" + UUID.randomUUID());
        notice.setNoticeType(noticeType);
        notice.setSummaryDate(summaryDate);
        notice.setBatchId(batchId);
        notice.setGroupId(909796613L);
        notice.setPayloadSnapshot(payloadSnapshot);
        notice.setPayloadHash(StockNoticePayloadCanonicalizer.sha256(payloadSnapshot));
        notice.setSendStatus(sendStatus);
        notice.setSendAttemptCount(attemptCount);
        notice.setClaimToken(claimToken);
        notice.setClaimTime(claimTime);
        notice.setMessageRuleVersion(MESSAGE_RULE_VERSION);
        assertTrue(noticeAuditDao.save(notice), "夹具通知必须写入成功");
        assertNotNull(notice.getId(), "夹具通知必须回填主键");
        createdNoticeIds.add(notice.getId());
        return notice.getId();
    }
}
