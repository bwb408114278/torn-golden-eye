package pn.torn.goldeneye.torn.service.stocks.alert.notice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeRebalanceGroupStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.rebalance.RebalanceGroupWriteResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 股票通知发送记录器 - 发送前领取、冻结最终payload并按发送结果回写可重发终态。
 * <p>
 * 普通通知与α换仓关联组共用本记录器,保证"数据库级领取""冻结行数必须完整""回写必须绑定领取标识"
 * 的口径只有一套实现:
 * <ul>
 *   <li>领取:只有PENDING/FAILED_RETRYABLE且未达3次总尝试上限的通知可被领取,领取即累计一次尝试;
 *       领取行数不足时释放本次领取并禁止调用Bot,避免重复消息</li>
 *   <li>冻结:只能冻结本领取者持有的SENDING通知,冻结UPDATE行数不等于通知数时禁止调用Bot</li>
 *   <li>回写:状态与领取标识必须同时匹配,旧领取者不能覆盖新状态;失败未达上限写FAILED_RETRYABLE
 *       由后续调度自动重发,达到上限写FAILED_FINAL终态;状态回写异常不回滚交易事实</li>
 * </ul>
 * α换仓关联组的组级领取/成功/失败/异常收敛是本类的统一入口:发送器不得自行按两条通知分别调用
 * 两个逐条回写方法,组级方法返回{@link RebalanceGroupWriteResult},实际行数不足时由调用方进入
 * 组级恢复或人工核验,禁止把部分更新解释为完整送达。
 * <p>
 * 所有时间都由调用方传入同一次发送编排的{@code businessNow},本类不直接读取系统时钟,
 * 也不允许同一流程出现两个不同瞬间的业务时间。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockNoticeSendRecorder {
    /**
     * 每条通知的总尝试次数上限(首次1次 + 自动重发2次)。
     * <p>
     * 领取、失败回写与租约恢复的SQL使用同一上限值,修改时必须同步
     * {@code TornStockNoticeAuditMapper.xml} 中的判断。
     */
    public static final int MAX_SEND_ATTEMPTS = 3;
    /**
     * α换仓关联组必须包含的腿数(一条SELL腿 + 一条BUY腿)。
     */
    private static final int REBALANCE_LEG_COUNT = 2;

    private final TornStockNoticeAuditDAO noticeAuditDao;

    /**
     * 生成本次发送的领取标识。
     * <p>
     * 每次发送流程使用独立标识,冻结与终态回写都必须携带该标识,保证旧领取者无法覆盖新状态。
     *
     * @return 领取标识
     */
    public String newClaimToken() {
        return UUID.randomUUID().toString();
    }

    /**
     * 原子领取一批通知。
     * <p>
     * 领取行数必须完整等于期望通知数:行数不足说明存在其他发送流程已持有的通知,
     * 本条消息禁止发送,并把本次已领到的通知释放回可重发状态,避免通知停留在SENDING。
     *
     * @param noticeIds   待领取通知ID列表
     * @param claimToken  本次领取标识
     * @param businessNow 本次发送编排的统一业务时间
     * @return 领取成功返回true;行数不足返回false且不调用Bot
     */
    public boolean claim(List<Long> noticeIds, String claimToken, LocalDateTime businessNow) {
        if (CollectionUtils.isEmpty(noticeIds)) {
            return false;
        }
        int claimed = noticeAuditDao.claimByIds(noticeIds, claimToken, businessNow);
        if (claimed != noticeIds.size()) {
            log.warn("股票通知发送-领取行数不足,禁止调用Bot: claimed={}, expected={}", claimed, noticeIds.size());
            releaseClaim(claimToken, businessNow);
            return false;
        }
        return true;
    }

    /**
     * 以换仓关联标识为单位原子领取α换仓关联组的两腿。
     * <p>
     * α换仓两腿必须以关联组为单位领取,不能只领取一条腿;组边界不完整(缺腿、重复腿、
     * 已SENT腿或已达上限腿)时数据库更新0行,本方法释放本次领取并禁止调用Bot。
     *
     * @param rebalanceAssociationId 换仓关联标识
     * @param expectedLegCount       本次预计可发送的腿数
     * @param claimToken             本次领取标识
     * @param businessNow            本次发送编排的统一业务时间
     * @return 组级领取结果;完整领取时{@code complete()}为true
     */
    public RebalanceGroupWriteResult claimRebalanceGroup(String rebalanceAssociationId, int expectedLegCount,
                                                         String claimToken, LocalDateTime businessNow) {
        if (rebalanceAssociationId == null || rebalanceAssociationId.isBlank() || expectedLegCount <= 0) {
            return RebalanceGroupWriteResult.of(REBALANCE_LEG_COUNT, 0,
                    StockNoticeRebalanceGroupStatusEnum.SENDING);
        }
        int claimed = noticeAuditDao.claimByRebalanceAssociationId(rebalanceAssociationId, claimToken, businessNow);
        RebalanceGroupWriteResult result = RebalanceGroupWriteResult.of(
                expectedLegCount, claimed, StockNoticeRebalanceGroupStatusEnum.SENDING);
        if (!result.complete()) {
            log.warn("股票通知发送-α换仓关联组领取行数不足,禁止调用Bot: associationId={}, claimed={}, expected={}",
                    rebalanceAssociationId, claimed, expectedLegCount);
            releaseClaim(claimToken, businessNow);
        }
        return result;
    }

    /**
     * 释放本次领取:把本领取标识持有且仍未回写的SENDING通知退回可重发/最终失败状态。
     * <p>
     * 领取不完整(并发流程已持有部分通知)时调用,保证未调用Bot的通知不会停留在SENDING等待租约,
     * 且不静默丢弃;已回写终态的通知不受影响。
     *
     * @param claimToken  本次领取标识
     * @param businessNow 本次发送编排的统一业务时间
     */
    public void releaseClaim(String claimToken, LocalDateTime businessNow) {
        if (claimToken == null || claimToken.isBlank()) {
            return;
        }
        try {
            int released = noticeAuditDao.releaseClaim(claimToken, businessNow);
            if (released > 0) {
                log.warn("股票通知发送-已释放未发送的领取: claimToken={}, released={}", claimToken, released);
            }
        } catch (Exception e) {
            log.error("股票通知发送-释放领取异常,等待领取租约超时恢复: claimToken={}", claimToken, e);
        }
    }

    /**
     * 在发送前逐条冻结最终消息载荷。
     * <p>
     * 对每条通知读取创建时业务payload,合并最终{@code messageText}与{@code frozenAt},
     * 保留全部业务字段(如formalReason/originalExitReason/recoveryBar等),不得覆盖。
     * 最终payload经 {@link StockNoticePayloadCanonicalizer} 规范化后计算哈希。
     * 冻结UPDATE行数必须等于通知数,否则返回false并由调用方停止发送本条消息,禁止发送不可审计消息;
     * 自动重发必须复用首次冻结的文本与哈希,不得重新组合正文。
     *
     * @param noticeById  通知ID索引
     * @param noticeIds   待冻结的通知ID列表
     * @param messageText 最终消息文本
     * @param frozenAt    首次冻结时间(自动重发复用已冻结腿的时间)
     * @param claimToken  本次领取标识
     * @param businessNow 本次发送编排的统一业务时间
     * @return 冻结成功(更新行数等于通知数)返回true;否则false
     */
    public boolean freezePayload(Map<Long, TornStockNoticeAuditDO> noticeById,
                                 List<Long> noticeIds,
                                 String messageText,
                                 LocalDateTime frozenAt,
                                 String claimToken,
                                 LocalDateTime businessNow) {
        if (CollectionUtils.isEmpty(noticeIds)) {
            return true;
        }
        List<NoticePayloadFinalizeCommand> commands = new ArrayList<>(noticeIds.size());
        for (Long noticeId : noticeIds) {
            TornStockNoticeAuditDO notice = noticeById.get(noticeId);
            if (notice == null) {
                log.error("股票通知发送-通知不存在,无法冻结payload: noticeId={}", noticeId);
                return false;
            }
            String finalPayload = StockNoticePayloadCanonicalizer.mergeAndCanonicalize(
                    notice.getPayloadSnapshot(), messageText, frozenAt);
            commands.add(new NoticePayloadFinalizeCommand(
                    noticeId, finalPayload, StockNoticePayloadCanonicalizer.sha256(finalPayload), claimToken));
        }
        int updated = noticeAuditDao.finalizePayload(commands, businessNow);
        if (updated != noticeIds.size()) {
            log.error("股票通知发送-最终payload冻结行数不符: updated={}, expected={}", updated, noticeIds.size());
            return false;
        }
        return true;
    }

    /**
     * 将本次领取成功的通知标记为已发送(SENT)并设置发送成功时间。
     * <p>
     * SENT为终态且不得再次发送。回写必须同时匹配SENDING状态与领取标识:更新行数不足说明存在
     * 状态不一致(如领取已被租约恢复或另一流程持有),必须记录ERROR以便审计发现,
     * 不得把单腿成功解释为完整换仓通知成功(发送已完成,不回滚交易事实)。
     *
     * @param noticeIds   本条消息对应的通知ID列表
     * @param claimToken  本次领取标识
     * @param businessNow 本次发送编排的统一业务时间
     * @return 更新行数完整时返回true
     */
    public boolean markSent(List<Long> noticeIds, String claimToken, LocalDateTime businessNow) {
        if (CollectionUtils.isEmpty(noticeIds)) {
            return false;
        }
        try {
            int updated = noticeAuditDao.markSentByIds(noticeIds, claimToken, businessNow);
            if (updated != noticeIds.size()) {
                log.error("股票通知发送-标记SENT行数不完整,同组通知状态不一致: updated={}, expected={}",
                        updated, noticeIds.size());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("股票通知发送-批量标记SENT状态异常,通知停留SENDING等待租约恢复, noticeCount={}",
                    noticeIds.size(), e);
            return false;
        }
    }

    /**
     * 将本次领取成功的通知标记为发送失败并记录实际错误信息。
     * <p>
     * 未达3次总尝试上限写入FAILED_RETRYABLE,由后续调度自动重发;达到上限写入FAILED_FINAL终态,
     * 不再自动发送。回写必须同时匹配SENDING状态与领取标识。回写异常时通知停留SENDING,
     * 由领取租约超时按"结果未知"恢复为可重发状态,不等待人工审核。
     *
     * @param noticeIds     本条消息对应的通知ID列表
     * @param claimToken    本次领取标识
     * @param failureReason 实际发送失败原因
     * @param businessNow   本次发送编排的统一业务时间
     * @return 更新行数完整时返回true
     */
    public boolean markSendFailed(List<Long> noticeIds, String claimToken, String failureReason,
                                  LocalDateTime businessNow) {
        if (CollectionUtils.isEmpty(noticeIds)) {
            return false;
        }
        try {
            int updated = noticeAuditDao.markSendFailedByIds(noticeIds, claimToken,
                    normalizeFailureReason(failureReason), businessNow);
            if (updated != noticeIds.size()) {
                log.error("股票通知发送-标记失败行数不完整,同组通知状态不一致: updated={}, expected={}",
                        updated, noticeIds.size());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("股票通知发送-批量标记失败状态异常,通知停留SENDING等待租约恢复, noticeCount={}",
                    noticeIds.size(), e);
            return false;
        }
    }

    /**
     * 将不可自动重发的通知置为人工核验终态(FAILED_FINAL)。
     * <p>
     * 用于关联批次不存在、α换仓通知缺少关联标识等不可解释状态:不调用Bot,
     * 也不进入自动重发,只保留明确错误信息供人工核验。
     *
     * @param noticeIds    通知ID列表
     * @param errorMessage 人工核验原因
     * @param businessNow  本次发送编排的统一业务时间
     * @return 更新行数完整时返回true
     */
    public boolean markFinal(List<Long> noticeIds, String errorMessage, LocalDateTime businessNow) {
        if (CollectionUtils.isEmpty(noticeIds)) {
            return false;
        }
        try {
            int updated = noticeAuditDao.markFinalByIds(noticeIds, errorMessage, businessNow);
            if (updated != noticeIds.size()) {
                log.error("股票通知发送-标记最终失败行数不完整,同组通知状态不一致: updated={}, expected={}",
                        updated, noticeIds.size());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("股票通知发送-批量标记最终失败状态异常, noticeCount={}", noticeIds.size(), e);
            return false;
        }
    }

    /**
     * 组级原子成功回写:两腿与组状态在同一SQL语义内进入SENT。
     * <p>
     * 实际更新行数必须为2:返回0不得解释为"没有变化的成功",返回1属于数据库或契约异常,
     * 两种情况都由调用方读取完整关联组并持久化收敛,禁止继续宣称完整送达。
     *
     * @param rebalanceAssociationId 换仓关联标识
     * @param claimToken             本次领取标识
     * @param businessNow            本次发送编排的统一业务时间
     * @return 组级回写结果
     */
    public RebalanceGroupWriteResult markRebalanceGroupSent(String rebalanceAssociationId, String claimToken,
                                                            LocalDateTime businessNow) {
        return writeGroup(rebalanceAssociationId, StockNoticeRebalanceGroupStatusEnum.SENT,
                REBALANCE_LEG_COUNT, "标记α换仓关联组SENT", () -> noticeAuditDao.markRebalanceGroupSent(
                        rebalanceAssociationId, claimToken, businessNow));
    }

    /**
     * 组级原子失败回写:两腿与组状态在同一次数据库语义内得到同一失败结果。
     * <p>
     * 只有两腿均为本领取者持有的SENDING且尝试次数一致时才更新2行,否则返回0行并由调用方
     * 进入异常收敛;禁止按较小尝试次数猜测重试次数。
     *
     * @param rebalanceAssociationId 换仓关联标识
     * @param claimToken             本次领取标识
     * @param failureReason          实际发送失败原因
     * @param businessNow            本次发送编排的统一业务时间
     * @return 组级回写结果;组状态为FAILED_RETRYABLE或FAILED_FINAL
     */
    public RebalanceGroupWriteResult markRebalanceGroupFailed(String rebalanceAssociationId, String claimToken,
                                                              String failureReason, LocalDateTime businessNow) {
        int updated;
        try {
            updated = noticeAuditDao.markRebalanceGroupFailed(rebalanceAssociationId, claimToken,
                    normalizeFailureReason(failureReason), businessNow);
        } catch (Exception e) {
            log.error("股票通知发送-标记α换仓关联组失败异常,按结果未知进入组级恢复: associationId={}",
                    rebalanceAssociationId, e);
            return RebalanceGroupWriteResult.of(REBALANCE_LEG_COUNT, 0,
                    StockNoticeRebalanceGroupStatusEnum.FAILED_FINAL);
        }
        StockNoticeRebalanceGroupStatusEnum groupStatus = resolvePersistedGroupStatus(rebalanceAssociationId, updated);
        RebalanceGroupWriteResult result = RebalanceGroupWriteResult.of(REBALANCE_LEG_COUNT, updated, groupStatus);
        if (!result.complete()) {
            log.error("股票通知发送-标记α换仓关联组失败行数不完整,不得宣称两腿一致失败: associationId={}, "
                    + "updated={}, expected={}", rebalanceAssociationId, updated, REBALANCE_LEG_COUNT);
        }
        return result;
    }

    /**
     * 组级异常终态收敛:把关联组内全部腿统一写入FAILED_FINAL/INCONSISTENT人工核验终态。
     * <p>
     * 缺腿、重复腿、字段冲突、一腿SENT而另一腿可重试、组回写行数不足等无法按正常组规则解释的状态
     * 必须持久化组级结果,不得只记录日志;已确认发送成功的腿保持SENT不被降级。
     *
     * @param rebalanceAssociationId 换仓关联标识
     * @param groupStatus            目标组状态(FAILED_FINAL或INCONSISTENT)
     * @param errorMessage           组级异常原因
     * @param expectedRows           该关联组当前实际腿数(缺腿组小于2时也必须可判定完整)
     * @param businessNow            本次发送编排的统一业务时间
     * @return 组级回写结果
     */
    public RebalanceGroupWriteResult convergeRebalanceGroup(String rebalanceAssociationId,
                                                            StockNoticeRebalanceGroupStatusEnum groupStatus,
                                                            String errorMessage, int expectedRows,
                                                            LocalDateTime businessNow) {
        return writeGroup(rebalanceAssociationId, groupStatus, expectedRows,
                "收敛α换仓关联组异常终态", () -> noticeAuditDao.convergeRebalanceGroup(
                        rebalanceAssociationId, groupStatus.getCode(), errorMessage, businessNow));
    }

    /**
     * 执行一次组级回写并把实际行数转换成显式结果对象。
     * <p>
     * 组级回写涉及数据库异常时同样按"结果未知"返回不完整结果,由调用方读取完整关联组收敛,
     * 不在本方法内吞掉异常并返回成功语义。
     *
     * @param rebalanceAssociationId 换仓关联标识
     * @param groupStatus            目标组状态
     * @param expectedRows           本次回写预期更新的行数
     * @param actionDescription      日志动作描述
     * @param writeAction            实际数据库回写动作
     * @return 组级回写结果
     */
    private RebalanceGroupWriteResult writeGroup(String rebalanceAssociationId,
                                                 StockNoticeRebalanceGroupStatusEnum groupStatus,
                                                 int expectedRows,
                                                 String actionDescription,
                                                 GroupWriteAction writeAction) {
        try {
            int updated = writeAction.execute();
            RebalanceGroupWriteResult result = RebalanceGroupWriteResult.of(expectedRows, updated, groupStatus);
            if (!result.complete()) {
                log.error("股票通知发送-{}更新行数不完整,不得宣称完整结果: associationId={}, updated={}, expected={}",
                        actionDescription, rebalanceAssociationId, updated, expectedRows);
            }
            return result;
        } catch (Exception e) {
            log.error("股票通知发送-{}异常,按结果未知进入组级恢复: associationId={}",
                    actionDescription, rebalanceAssociationId, e);
            return RebalanceGroupWriteResult.of(expectedRows, 0, groupStatus);
        }
    }

    /**
     * 读取数据库实际持久化的α换仓组状态。
     * <p>
     * 失败回写由数据库按两腿尝试次数决定路由为FAILED_RETRYABLE或FAILED_FINAL,
     * 因此行数完整时必须读回真实组状态,而不是由调用方猜测。
     *
     * @param rebalanceAssociationId 换仓关联标识
     * @param updated                失败回写实际更新行数
     * @return 读回的组状态;行数不足或无法解析时返回FAILED_FINAL
     */
    private StockNoticeRebalanceGroupStatusEnum resolvePersistedGroupStatus(String rebalanceAssociationId,
                                                                            int updated) {
        if (updated != REBALANCE_LEG_COUNT) {
            return StockNoticeRebalanceGroupStatusEnum.FAILED_FINAL;
        }
        List<TornStockNoticeAuditDO> group = noticeAuditDao.selectByRebalanceAssociationId(rebalanceAssociationId);
        for (TornStockNoticeAuditDO leg : group) {
            String code = leg.getRebalanceGroupStatus();
            if (code == null || code.isBlank()) {
                continue;
            }
            try {
                return StockNoticeRebalanceGroupStatusEnum.fromCode(code);
            } catch (IllegalArgumentException e) {
                log.error("股票通知发送-α换仓组状态编码无法解析: associationId={}, groupStatus={}",
                        rebalanceAssociationId, code);
                return StockNoticeRebalanceGroupStatusEnum.FAILED_FINAL;
            }
        }
        return StockNoticeRebalanceGroupStatusEnum.FAILED_FINAL;
    }

    /**
     * 归一化失败原因,避免空原因写入审计。
     *
     * @param failureReason 原始失败原因
     * @return 非空失败原因
     */
    private String normalizeFailureReason(String failureReason) {
        return failureReason == null || failureReason.isBlank()
                ? StockNoticeBotSender.NULL_RESPONSE_FAILURE_MESSAGE : failureReason;
    }

    /**
     * 组级数据库回写动作。
     */
    @FunctionalInterface
    private interface GroupWriteAction {
        /**
         * 执行数据库回写。
         *
         * @return 实际更新行数
         */
        int execute();
    }
}
