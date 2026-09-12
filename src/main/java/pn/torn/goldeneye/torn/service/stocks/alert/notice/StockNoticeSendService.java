package pn.torn.goldeneye.torn.service.stocks.alert.notice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeTypeEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.rebalance.StockRebalanceNoticeSender;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 股票通知发送服务 - 事务提交后领取可发送通知、组合中文消息并驱动投递。
 * <p>
 * 在VIP股票策略轮次事务提交后驱动消息投递。整体流程:
 * <ol>
 *   <li>校验 {@link SettingConstants#KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED} 开关(值为"true"时启用)</li>
 *   <li>先恢复领取租约超时仍未回写的通知(进程崩溃或回写异常时按结果未知回到可重发/最终失败)</li>
 *   <li>查询可发送通知(PENDING与未达上限的FAILED_RETRYABLE),批量查询关联批次信息,
 *       无有效批次的通知置为FAILED_FINAL人工核验终态</li>
 *   <li>按通知类型分流: α换仓通知交由 {@link StockRebalanceNoticeSender} 以完整两腿关联组
 *       组合成一条原子消息发送;普通通知调用
 *       {@link StockNoticeComposeService#composeAndMergeNotices} 组合未冻结通知,
 *       已冻结通知(快照已有messageText与frozenAt)按原冻结文本直接投递,不再重新组合</li>
 *   <li>每条消息发送前必须以数据库级领取为唯一入口:领取行数不足不得调用Bot并释放本次领取;
 *       未冻结通知在领取后冻结最终payload并校验更新行数</li>
 *   <li>发送经 {@link StockNoticeBotSender} 执行,状态回写经 {@link StockNoticeSendRecorder} 执行;
 *       失败未达3次总尝试上限进入FAILED_RETRYABLE由后续调度自动重发,达到上限进入FAILED_FINAL,
 *       SENT不得再次发送</li>
 * </ol>
 * 单条通知发送异常不会中断后续通知投递,异常信息写入errorMessage字段。
 * <p>
 * 本服务只负责本轮调度的编排与普通通知路径,载荷解析、领取与发送结果回写、Bot发送和α换仓关联组闭包
 * 分别由 {@link StockNoticePayloadReader}、{@link StockNoticeSendRecorder}、
 * {@link StockNoticeBotSender}、{@link StockRebalanceNoticeSender} 承担。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.07.25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockNoticeSendService {
    /**
     * 开关启用标识(仅当配置值为"true"时启用,忽略大小写)
     */
    private static final String SETTING_ENABLED_VALUE = "true";
    /**
     * 通知缺少关联批次时的统一失败原因。
     */
    private static final String MISSING_BATCH_FAILURE_MESSAGE = "关联虚拟交易批次不存在";
    /**
     * 批量通知缺少可发送正文时的统一失败原因。
     */
    private static final String MISSING_MESSAGE_TEXT_FAILURE_MESSAGE = "通知载荷缺少可发送正文";
    /**
     * 发送前冻结失败(更新行数不符)时的统一失败原因。
     */
    private static final String FINALIZE_FAILURE_MESSAGE = "发送前最终payload冻结行数不符";
    /**
     * 发送领取租约分钟数:领取后超过该时间仍未回写的SENDING通知按结果未知恢复。
     */
    private static final long CLAIM_LEASE_MINUTES = 5;

    private final SysSettingManager sysSettingManager;
    private final TornStockNoticeAuditDAO noticeAuditDao;
    private final TornStockVirtualBatchDAO virtualBatchDao;
    private final StockNoticeComposeService stockNoticeComposeService;
    private final StockNoticeBotSender botSender;
    private final StockNoticeSendRecorder sendRecorder;
    private final StockRebalanceNoticeSender rebalanceNoticeSender;

    /**
     * 发送全部可发送通知(PENDING与未达尝试上限的可重发通知)。
     * <p>
     * 事务提交后调用。执行流程:
     * <ol>
     *   <li>校验 {@link SettingConstants#KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED} 开关,非"true"直接返回</li>
     *   <li>恢复领取租约超时的SENDING通知,保证崩溃或回写异常后状态可解释且可自动重发</li>
     *   <li>查询可发送通知,无记录直接返回</li>
     *   <li>批量查询关联批次信息(用通知的batchId集合),买卖类通知无有效批次时置为FAILED_FINAL</li>
     *   <li>α换仓通知按{@code rebalanceAssociationId}成组交由 {@link StockRebalanceNoticeSender}
     *       读取完整两腿、以关联组为单位领取并只调用Bot一次;其余通知组合合并后逐条领取发送</li>
     *   <li>逐条发送:领取成功后才冻结未冻结payload;HTTP 2xx且body非空且NapCat业务成功时更新SENT;
     *       失败未达上限更新FAILED_RETRYABLE等待自动重发,达到上限更新FAILED_FINAL并记录errorMessage</li>
     * </ol>
     * α换仓关联组缺腿、重复腿、字段冲突、格式非法或部分完成时由发送器fail-closed,不调用Bot并
     * 置为人工核验终态;单条通知发送异常不中断后续投递,整个方法不抛出异常(内部捕获并记录)。
     */
    public void sendPendingNotices() {
        String enabled = sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED);
        if (!SETTING_ENABLED_VALUE.equalsIgnoreCase(enabled)) {
            log.debug("股票通知发送-正式买卖消息开关关闭,跳过发送");
            return;
        }

        recoverStaleClaims();

        List<TornStockNoticeAuditDO> sendableNotices = noticeAuditDao.selectSendableNotices();
        if (CollectionUtils.isEmpty(sendableNotices)) {
            log.debug("股票通知发送-无可发送通知");
            return;
        }

        log.info("股票通知发送-发现{}条可发送通知", sendableNotices.size());

        Map<Long, TornStockVirtualBatchDO> batchMap = loadBatchMap(sendableNotices);
        List<TornStockNoticeAuditDO> validNotices = filterNoticesWithoutRequiredBatch(sendableNotices, batchMap);
        if (validNotices.size() < sendableNotices.size()) {
            markMissingBatchNoticesFinal(sendableNotices, batchMap);
        }
        if (validNotices.isEmpty()) {
            log.warn("股票通知发送-无有效批次关联, sendableNotices={}", sendableNotices.size());
            return;
        }

        NoticeSendCounter counter = new NoticeSendCounter();
        List<TornStockNoticeAuditDO> normalNotices = new ArrayList<>(validNotices.size());
        List<TornStockNoticeAuditDO> rebalanceNotices = new ArrayList<>();
        classifyNotices(validNotices, rebalanceNotices, normalNotices);
        // α换仓关联组必须以完整两腿组合成一条原子消息发送,不得与普通通知合并后被拆分
        rebalanceNoticeSender.sendGroups(rebalanceNotices, batchMap, counter);
        sendNormalNotices(normalNotices, batchMap, counter);

        log.info("股票通知发送-完成, 成功={}条, 失败={}条", counter.successCount(), counter.failedCount());
    }

    /**
     * 恢复领取租约超时仍未回写的通知。
     * <p>
     * 领取成功者进程崩溃、回写异常或数据库异常都会让通知停留在SENDING:超过租约后按"结果未知"
     * 处理,未达尝试上限恢复为FAILED_RETRYABLE等待自动重发,达到上限恢复为FAILED_FINAL终态;
     * 恢复失败不阻塞本轮,后续调度继续尝试。
     */
    private void recoverStaleClaims() {
        try {
            int recovered = noticeAuditDao.recoverStaleClaims(LocalDateTime.now().minusMinutes(CLAIM_LEASE_MINUTES));
            if (recovered > 0) {
                log.warn("股票通知发送-恢复领取超时的通知, count={}", recovered);
            }
        } catch (Exception e) {
            log.error("股票通知发送-恢复领取超时通知异常,本轮继续", e);
        }
    }

    /**
     * 将本轮可发送通知按是否为α换仓关联组成员分流。
     * <p>
     * α换仓通知与任何携带换仓关联标识的通知都必须按payload固化的换仓关联标识成组处理,
     * 禁止当作单腿普通消息发送;其余通知走普通组合链。
     *
     * @param validNotices     本轮可发送通知
     * @param rebalanceNotices α换仓通知收集结果
     * @param normalNotices    普通通知收集结果
     */
    private void classifyNotices(List<TornStockNoticeAuditDO> validNotices,
                                 List<TornStockNoticeAuditDO> rebalanceNotices,
                                 List<TornStockNoticeAuditDO> normalNotices) {
        for (TornStockNoticeAuditDO notice : validNotices) {
            if (StockRebalanceNoticeSender.isRebalanceGroupMember(notice)) {
                rebalanceNotices.add(notice);
            } else {
                normalNotices.add(notice);
            }
        }
    }

    /**
     * 组合并发送普通(非α换仓)通知。
     * <p>
     * 未冻结通知重新组合,已冻结通知复用首次冻结文本;每条消息发送前先以数据库级领取为唯一入口,
     * 领取行数不足时不调用Bot,领取成功后冻结仍需冻结的通知并校验更新行数。
     *
     * @param normalNotices 普通可发送通知
     * @param batchMap      批次索引
     * @param counter       发送计数
     */
    private void sendNormalNotices(List<TornStockNoticeAuditDO> normalNotices,
                                   Map<Long, TornStockVirtualBatchDO> batchMap,
                                   NoticeSendCounter counter) {
        if (CollectionUtils.isEmpty(normalNotices)) {
            return;
        }
        Map<Long, TornStockNoticeAuditDO> noticeById = indexNoticesById(normalNotices);
        List<TornStockNoticeAuditDO> composableNotices = excludeNoticesWithoutMessageText(normalNotices, counter);
        if (CollectionUtils.isEmpty(composableNotices)) {
            return;
        }
        List<StockNoticeComposeService.ComposedMessage> composedMessages =
                composePendingMessages(composableNotices, batchMap);
        if (CollectionUtils.isEmpty(composedMessages)) {
            log.warn("股票通知发送-消息组合结果为空,待发送通知数={}", composableNotices.size());
            return;
        }
        sendComposedMessages(composedMessages, noticeById, counter);
    }

    /**
     * 排除既未冻结又缺少可发送正文的无批次通知。
     * <p>
     * 无关联批次的通知(如每日摘要)正文固化在创建时载荷中:已冻结时直接复用冻结文本,
     * 未冻结但存在messageText时按原正文补冻结;两者都不存在属于不可解释状态,
     * 直接置为人工核验终态,不得静默丢弃也不得重新生成正文。
     *
     * @param normalNotices 普通可发送通知
     * @param counter       发送计数
     * @return 可进入组合链的通知
     */
    private List<TornStockNoticeAuditDO> excludeNoticesWithoutMessageText(
            List<TornStockNoticeAuditDO> normalNotices, NoticeSendCounter counter) {
        List<TornStockNoticeAuditDO> composable = new ArrayList<>(normalNotices.size());
        List<Long> corruptNoticeIds = new ArrayList<>();
        for (TornStockNoticeAuditDO notice : normalNotices) {
            if (requiresBatch(notice) || StockNoticePayloadReader.isAlreadyFrozen(notice)
                    || StockNoticePayloadReader.readMessageText(notice) != null) {
                composable.add(notice);
                continue;
            }
            if (notice.getId() != null) {
                corruptNoticeIds.add(notice.getId());
            }
        }
        if (!corruptNoticeIds.isEmpty()) {
            counter.countFailure();
            log.error("股票通知发送-通知既未冻结又缺少可发送正文,置为人工核验终态: noticeIds={}", corruptNoticeIds);
            sendRecorder.markFinal(corruptNoticeIds, MISSING_MESSAGE_TEXT_FAILURE_MESSAGE);
        }
        return composable;
    }

    /**
     * 逐条领取并发送已组合的普通消息,按领取标识回写发送终态。
     * <p>
     * 领取是唯一的发送入口:领取行数不足说明通知已被其他发送流程持有,本条消息不调用Bot;
     * 领取成功后只冻结尚未冻结的通知,已冻结通知重启投递不得再次冻结,
     * 避免覆盖首次冻结的payloadSnapshot/payloadHash/frozenAt。
     *
     * @param composedMessages 已组合消息
     * @param noticeById       通知ID索引
     * @param counter          发送计数
     */
    private void sendComposedMessages(List<StockNoticeComposeService.ComposedMessage> composedMessages,
                                      Map<Long, TornStockNoticeAuditDO> noticeById,
                                      NoticeSendCounter counter) {
        for (StockNoticeComposeService.ComposedMessage composedMessage : composedMessages) {
            List<Long> noticeIds = composedMessage.noticeIds();
            String claimToken = sendRecorder.newClaimToken();
            if (!sendRecorder.claim(noticeIds, claimToken)) {
                counter.countFailure();
                log.warn("股票通知发送-本条合并消息未领取成功,跳过发送: noticeIds={}", noticeIds);
                continue;
            }
            if (!freezeComposedMessage(noticeById, composedMessage, claimToken)) {
                counter.countFailure();
                log.error("股票通知发送-最终payload冻结行数不符,停止发送本条合并消息: noticeCount={}",
                        noticeIds.size());
                sendRecorder.markSendFailed(noticeIds, claimToken, FINALIZE_FAILURE_MESSAGE);
                continue;
            }
            deliverComposedMessage(composedMessage, claimToken, counter);
        }
    }

    /**
     * 发送前冻结本条合并消息中尚未冻结的通知。
     *
     * @param noticeById      通知ID索引
     * @param composedMessage 已组合消息
     * @param claimToken      本次领取标识
     * @return 无需冻结或冻结成功返回true;行数不符返回false
     */
    private boolean freezeComposedMessage(Map<Long, TornStockNoticeAuditDO> noticeById,
                                          StockNoticeComposeService.ComposedMessage composedMessage,
                                          String claimToken) {
        List<Long> unfrozenNoticeIds = composedMessage.noticeIds().stream()
                .filter(noticeId -> !StockNoticePayloadReader.isAlreadyFrozen(noticeById.get(noticeId)))
                .toList();
        if (unfrozenNoticeIds.isEmpty()) {
            return true;
        }
        return sendRecorder.freezePayload(noticeById, unfrozenNoticeIds, composedMessage.text(),
                LocalDateTime.now(), claimToken);
    }

    /**
     * 调用Bot发送一条已领取消息并按结果回写通知终态。
     *
     * @param composedMessage 已组合消息
     * @param claimToken      本次领取标识
     * @param counter         发送计数
     */
    private void deliverComposedMessage(StockNoticeComposeService.ComposedMessage composedMessage,
                                        String claimToken,
                                        NoticeSendCounter counter) {
        StockNoticeBotSender.SendResult sendResult = botSender.send(composedMessage.text());
        if (sendResult.success()) {
            counter.countSuccess();
            sendRecorder.markSent(composedMessage.noticeIds(), claimToken);
        } else {
            counter.countFailure();
            sendRecorder.markSendFailed(composedMessage.noticeIds(), claimToken, sendResult.failureReason());
        }
    }

    /**
     * 组合尚未冻结的通知,并复用进程中断前已经冻结的通知文本。
     * <p>
     * 已冻结通知(载荷同时存在messageText与frozenAt)按原冻结文本分组直接发送,不进入重新组合,
     * 因此自动重发只复用首次冻结的文本与哈希;无关联批次的通知正文固化在创建时载荷中,
     * 未冻结时按原正文补冻结;其余未冻结通知调用
     * {@link StockNoticeComposeService#composeAndMergeNotices} 重新组合。
     *
     * @param notices  可组合通知
     * @param batchMap 批次索引
     * @return 待发送的最终消息列表
     */
    private List<StockNoticeComposeService.ComposedMessage> composePendingMessages(
            List<TornStockNoticeAuditDO> notices,
            Map<Long, TornStockVirtualBatchDO> batchMap) {
        Map<String, List<Long>> frozenNoticeIdsByText = new LinkedHashMap<>();
        List<TornStockNoticeAuditDO> noticesToCompose = new ArrayList<>();
        List<StockNoticeComposeService.ComposedMessage> selfContainedMessages = new ArrayList<>();
        for (TornStockNoticeAuditDO notice : notices) {
            if (StockNoticePayloadReader.isAlreadyFrozen(notice)) {
                String frozenText = StockNoticePayloadReader.readFrozenMessageText(notice.getPayloadSnapshot());
                frozenNoticeIdsByText.computeIfAbsent(frozenText, ignored -> new ArrayList<>())
                        .add(notice.getId());
            } else if (requiresBatch(notice)) {
                noticesToCompose.add(notice);
            } else {
                selfContainedMessages.add(new StockNoticeComposeService.ComposedMessage(
                        List.of(notice.getId()), StockNoticePayloadReader.readMessageText(notice)));
            }
        }

        List<StockNoticeComposeService.ComposedMessage> messages = new ArrayList<>(selfContainedMessages);
        frozenNoticeIdsByText.forEach((text, noticeIds) ->
                messages.add(new StockNoticeComposeService.ComposedMessage(noticeIds, text)));
        messages.addAll(stockNoticeComposeService.composeAndMergeNotices(noticesToCompose, batchMap));
        return messages;
    }

    /**
     * 发送单条群消息。
     * <p>
     * 构建群消息请求(目标群为 {@code ProjectProperty#getVipGroupId()}),添加文本消息并调用Bot发送;
     * 成功判定与失败原因由 {@link StockNoticeBotSender} 统一给出,本方法只保留布尔结果。
     *
     * @param text 待发送的中文消息文本
     * @return true表示发送成功;false表示发送失败、响应异常或无法确认成功
     */
    public boolean sendSingleMessage(String text) {
        return sendSingleMessageResult(text).success();
    }

    /**
     * 发送单条群消息并返回可审计的失败原因。
     * <p>
     * 日报摘要等自包含通知需要把失败原因写入通知审计,因此统一由本方法暴露
     * {@link StockNoticeBotSender.SendResult},避免各处自行判定成功。
     *
     * @param text 待发送的中文消息文本
     * @return 发送结果;失败时携带实际失败原因
     */
    public StockNoticeBotSender.SendResult sendSingleMessageResult(String text) {
        return botSender.send(text);
    }

    /**
     * 批量加载通知关联的虚拟交易批次信息
     * <p>
     * 收集全部通知的batchId,批量查询 {@link TornStockVirtualBatchDAO#listByIds(java.util.Collection)}
     * 避免N+1查询,构建batchId到批次DO的映射。batchId为null的通知不参与映射。
     *
     * @param notices 待发送通知列表
     * @return batchId到批次DO的映射;无有效batchId时返回空Map
     */
    private Map<Long, TornStockVirtualBatchDO> loadBatchMap(List<TornStockNoticeAuditDO> notices) {
        Set<Long> batchIds = notices.stream()
                .map(TornStockNoticeAuditDO::getBatchId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (batchIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<TornStockVirtualBatchDO> batches = virtualBatchDao.listByIds(batchIds);
        if (CollectionUtils.isEmpty(batches)) {
            return Collections.emptyMap();
        }
        return batches.stream()
                .collect(Collectors.toMap(TornStockVirtualBatchDO::getId, Function.identity()));
    }

    /**
     * 过滤没有关联批次的买卖类通知。
     * <p>
     * 只有买卖类通知(含α换仓)必须关联虚拟交易批次;每日摘要等无批次通知正文自包含,
     * 不因缺少批次被终止,否则崩溃恢复后的摘要会被误判为批次不存在。
     *
     * @param notices  待发送通知
     * @param batchMap 批次索引
     * @return 存在关联批次或本身不需要批次的通知
     */
    private List<TornStockNoticeAuditDO> filterNoticesWithoutRequiredBatch(
            List<TornStockNoticeAuditDO> notices,
            Map<Long, TornStockVirtualBatchDO> batchMap) {
        return notices.stream()
                .filter(notice -> !requiresBatch(notice)
                        || (notice.getBatchId() != null && batchMap.containsKey(notice.getBatchId())))
                .toList();
    }

    /**
     * 判断通知是否必须关联虚拟交易批次。
     * <p>
     * 只有每日摘要类通知正文自包含、不关联批次;其余通知类型(含未知类型)一律要求有效批次,
     * 避免未知类型通知绕过批次校验被判为可发送。
     *
     * @param notice 通知审计
     * @return 需要关联批次返回true;每日摘要返回false
     */
    private boolean requiresBatch(TornStockNoticeAuditDO notice) {
        return !StockNoticeTypeEnum.DAILY_SUMMARY.getCode().equals(notice.getNoticeType());
    }

    /**
     * 批量终止无法关联批次的买卖类通知,避免永久重复扫描。
     * <p>
     * 批次不存在或已被删除属于不可自动重发的不可解释状态,直接进入FAILED_FINAL人工核验终态。
     *
     * @param notices  待发送通知
     * @param batchMap 已加载的批次索引
     */
    private void markMissingBatchNoticesFinal(List<TornStockNoticeAuditDO> notices,
                                              Map<Long, TornStockVirtualBatchDO> batchMap) {
        List<Long> missingNoticeIds = notices.stream()
                .filter(notice -> requiresBatch(notice)
                        && (notice.getBatchId() == null || !batchMap.containsKey(notice.getBatchId())))
                .map(TornStockNoticeAuditDO::getId)
                .filter(Objects::nonNull)
                .toList();
        if (!missingNoticeIds.isEmpty()) {
            sendRecorder.markFinal(missingNoticeIds, MISSING_BATCH_FAILURE_MESSAGE);
            log.warn("股票通知发送-无关联批次通知已标记FAILED_FINAL: count={}", missingNoticeIds.size());
        }
    }

    /**
     * 将通知列表按ID索引,用于逐条payload冻结。
     *
     * @param notices 通知列表
     * @return 通知ID到通知的映射
     */
    private Map<Long, TornStockNoticeAuditDO> indexNoticesById(List<TornStockNoticeAuditDO> notices) {
        Map<Long, TornStockNoticeAuditDO> map = new HashMap<>();
        if (notices == null) {
            return map;
        }
        for (TornStockNoticeAuditDO notice : notices) {
            if (notice.getId() != null) {
                map.put(notice.getId(), notice);
            }
        }
        return map;
    }
}
