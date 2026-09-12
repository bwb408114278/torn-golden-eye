package pn.torn.goldeneye.torn.service.stocks.alert.notice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.SettingConstants;
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
 * 股票通知发送服务 - 事务提交后查询PENDING通知、组合中文消息并驱动投递。
 * <p>
 * 在VIP股票策略轮次事务提交后驱动消息投递。整体流程:
 * <ol>
 *   <li>校验 {@link SettingConstants#KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED} 开关(值为"true"时启用)</li>
 *   <li>查询全部PENDING通知 {@link TornStockNoticeAuditDAO#selectPendingNotices()}</li>
 *   <li>批量查询关联批次信息(用通知的batchId集合),无有效批次的通知标记FAILED</li>
 *   <li>按通知类型分流: α换仓通知交由 {@link StockRebalanceNoticeSender} 以完整两腿关联组
 *       组合成一条原子消息发送;普通通知调用
 *       {@link StockNoticeComposeService#composeAndMergeNotices} 组合未冻结通知,
 *       已冻结通知(快照已有messageText与frozenAt)按原冻结文本直接投递,不再重新组合</li>
 *   <li>未冻结通知先逐条冻结最终payload并校验更新行数,再调用Bot发送;
 *       已冻结通知不调用冻结更新,直接发送,仅按发送结果更新SENT/FAILED</li>
 *   <li>发送经 {@link StockNoticeBotSender} 执行,状态回写经 {@link StockNoticeSendRecorder} 执行;
 *       本期不自动重试,sendAttemptCount从0改为1</li>
 * </ol>
 * 单条通知发送异常不会中断后续通知投递,异常信息写入errorMessage字段。
 * <p>
 * 本服务只负责本轮调度的编排与普通通知路径,载荷解析、Bot发送、发送结果回写和α换仓关联组闭包
 * 分别由 {@link StockNoticePayloadReader}、{@link StockNoticeBotSender}、
 * {@link StockNoticeSendRecorder}、{@link StockRebalanceNoticeSender} 承担。
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

    private final SysSettingManager sysSettingManager;
    private final TornStockNoticeAuditDAO noticeAuditDao;
    private final TornStockVirtualBatchDAO virtualBatchDao;
    private final StockNoticeComposeService stockNoticeComposeService;
    private final StockNoticeBotSender botSender;
    private final StockNoticeSendRecorder sendRecorder;
    private final StockRebalanceNoticeSender rebalanceNoticeSender;

    /**
     * 发送全部待发送(PENDING)通知。
     * <p>
     * 事务提交后调用。执行流程:
     * <ol>
     *   <li>校验 {@link SettingConstants#KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED} 开关,非"true"直接返回</li>
     *   <li>查询全部PENDING通知,无记录直接返回</li>
     *   <li>批量查询关联批次信息(用通知的batchId集合),无有效批次的通知标记FAILED</li>
     *   <li>α换仓通知按{@code rebalanceAssociationId}成组交由 {@link StockRebalanceNoticeSender}
     *       读取完整两腿后统一冻结并只调用Bot一次;其余通知调用
     *       {@link StockNoticeComposeService#composeAndMergeNotices} 组合并拆分消息</li>
     *   <li>发送前逐条冻结最终payload并校验更新行数,行数不符时不调用Bot</li>
     *   <li>逐条发送: HTTP 2xx且body非空且NapCat业务成功时更新SENT;异常、非2xx、body为null或
     *       NapCat业务失败时更新FAILED并记录errorMessage</li>
     * </ol>
     * α换仓关联组缺腿、重复腿、字段冲突、格式非法或部分完成时由发送器fail-closed,不调用Bot并
     * 记录人工核验原因;单条通知发送异常不中断后续投递,整个方法不抛出异常(内部捕获并记录)。
     */
    public void sendPendingNotices() {
        String enabled = sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED);
        if (!SETTING_ENABLED_VALUE.equalsIgnoreCase(enabled)) {
            log.debug("股票通知发送-正式买卖消息开关关闭,跳过发送");
            return;
        }

        List<TornStockNoticeAuditDO> pendingNotices = noticeAuditDao.selectPendingNotices();
        if (CollectionUtils.isEmpty(pendingNotices)) {
            log.debug("股票通知发送-无待发送通知");
            return;
        }

        log.info("股票通知发送-发现{}条待发送通知", pendingNotices.size());

        Map<Long, TornStockVirtualBatchDO> batchMap = loadBatchMap(pendingNotices);
        List<TornStockNoticeAuditDO> validNotices = filterNoticesWithBatches(pendingNotices, batchMap);
        if (validNotices.size() < pendingNotices.size()) {
            markMissingBatchNoticesFailed(pendingNotices, batchMap);
        }
        if (validNotices.isEmpty()) {
            log.warn("股票通知发送-无有效批次关联, pendingNotices={}", pendingNotices.size());
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
     * 将本轮有效PENDING通知按是否为α换仓关联组成员分流。
     * <p>
     * α换仓通知与任何携带换仓关联标识的通知都必须按payload固化的换仓关联标识成组处理,
     * 禁止当作单腿普通消息发送;其余通知走普通组合链。
     *
     * @param validNotices     本轮有效PENDING通知
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
     * 未冻结通知重新组合,已冻结通知复用首次冻结文本;发送前逐条冻结最终payload并校验更新行数,
     * 行数不符时停止本条消息且不调用Bot。
     *
     * @param normalNotices 普通PENDING通知
     * @param batchMap      批次索引
     * @param counter       发送计数
     */
    private void sendNormalNotices(List<TornStockNoticeAuditDO> normalNotices,
                                   Map<Long, TornStockVirtualBatchDO> batchMap,
                                   NoticeSendCounter counter) {
        if (CollectionUtils.isEmpty(normalNotices)) {
            return;
        }
        List<StockNoticeComposeService.ComposedMessage> composedMessages =
                composePendingMessages(normalNotices, batchMap);
        if (CollectionUtils.isEmpty(composedMessages)) {
            log.warn("股票通知发送-消息组合结果为空,待发送通知数={}", normalNotices.size());
            return;
        }
        Map<Long, TornStockNoticeAuditDO> noticeById = indexNoticesById(normalNotices);
        Set<Long> frozenNoticeIds = normalNotices.stream()
                .filter(StockNoticePayloadReader::isAlreadyFrozen)
                .map(TornStockNoticeAuditDO::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        sendComposedMessages(composedMessages, noticeById, frozenNoticeIds, counter);
    }

    /**
     * 逐条发送已组合的普通消息并回写单次发送终态。
     * <p>
     * 已冻结通知重启投递不得再次冻结,避免覆盖首次冻结的payloadSnapshot/payloadHash/frozenAt/attemptedAt;
     * 冻结行数不符时停止本条消息且不调用Bot。
     *
     * @param composedMessages 已组合消息
     * @param noticeById       通知ID索引
     * @param frozenNoticeIds  已冻结通知ID集合
     * @param counter          发送计数
     */
    private void sendComposedMessages(List<StockNoticeComposeService.ComposedMessage> composedMessages,
                                      Map<Long, TornStockNoticeAuditDO> noticeById,
                                      Set<Long> frozenNoticeIds,
                                      NoticeSendCounter counter) {
        for (StockNoticeComposeService.ComposedMessage composedMessage : composedMessages) {
            boolean needsFreeze = composedMessage.noticeIds().isEmpty()
                    || !frozenNoticeIds.containsAll(composedMessage.noticeIds());
            if (needsFreeze && !freezeComposedMessage(noticeById, composedMessage, counter)) {
                log.error("股票通知发送-最终payload冻结行数不符,停止发送本条合并消息: noticeCount={}",
                        composedMessage.noticeIds().size());
            } else {
                deliverComposedMessage(composedMessage, counter);
            }
        }
    }

    /**
     * 冻结本条合并消息涉及的全部未冻结通知。
     *
     * @param noticeById      通知ID索引
     * @param composedMessage 已组合消息
     * @param counter         发送计数
     * @return 冻结成功返回true;行数不符返回false并计入失败
     */
    private boolean freezeComposedMessage(Map<Long, TornStockNoticeAuditDO> noticeById,
                                          StockNoticeComposeService.ComposedMessage composedMessage,
                                          NoticeSendCounter counter) {
        boolean frozen = sendRecorder.freezePayload(
                noticeById, composedMessage.noticeIds(), composedMessage.text(), LocalDateTime.now());
        if (!frozen) {
            counter.countFailure();
        }
        return frozen;
    }

    /**
     * 调用Bot发送一条已冻结消息并按结果回写通知终态。
     *
     * @param composedMessage 已组合消息
     * @param counter         发送计数
     */
    private void deliverComposedMessage(StockNoticeComposeService.ComposedMessage composedMessage,
                                        NoticeSendCounter counter) {
        StockNoticeBotSender.SendResult sendResult = botSender.send(composedMessage.text());
        if (sendResult.success()) {
            counter.countSuccess();
            sendRecorder.markSent(composedMessage.noticeIds());
        } else {
            counter.countFailure();
            sendRecorder.markSendFailed(composedMessage.noticeIds(), sendResult.failureReason());
        }
    }

    /**
     * 组合尚未冻结的通知,并复用进程中断前已经冻结的通知文本。
     * <p>
     * 已冻结通知判定要求payload快照同时存在有效的{@code messageText}与{@code frozenAt}:
     * 已冻结通知按原冻结文本分组直接发送,不进入重新组合;
     * 未冻结通知调用 {@link StockNoticeComposeService#composeAndMergeNotices} 重新组合。
     * 两种通知不会被混合到同一条消息后再次冻结。
     *
     * @param notices  有效待发送通知
     * @param batchMap 批次索引
     * @return 待发送的最终消息列表
     */
    private List<StockNoticeComposeService.ComposedMessage> composePendingMessages(
            List<TornStockNoticeAuditDO> notices,
            Map<Long, TornStockVirtualBatchDO> batchMap) {
        Map<String, List<Long>> frozenNoticeIdsByText = new LinkedHashMap<>();
        List<TornStockNoticeAuditDO> noticesToCompose = new ArrayList<>();
        for (TornStockNoticeAuditDO notice : notices) {
            if (StockNoticePayloadReader.isAlreadyFrozen(notice)) {
                String frozenText = StockNoticePayloadReader.readFrozenMessageText(notice.getPayloadSnapshot());
                frozenNoticeIdsByText.computeIfAbsent(frozenText, ignored -> new ArrayList<>())
                        .add(notice.getId());
            } else {
                noticesToCompose.add(notice);
            }
        }

        List<StockNoticeComposeService.ComposedMessage> messages = new ArrayList<>();
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
        return botSender.send(text).success();
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
     * 过滤没有关联批次的通知。
     *
     * @param notices  待发送通知
     * @param batchMap 批次索引
     * @return 存在关联批次的通知
     */
    private List<TornStockNoticeAuditDO> filterNoticesWithBatches(
            List<TornStockNoticeAuditDO> notices,
            Map<Long, TornStockVirtualBatchDO> batchMap) {
        return notices.stream()
                .filter(notice -> notice.getBatchId() != null
                        && batchMap.containsKey(notice.getBatchId()))
                .toList();
    }

    /**
     * 批量终止无法关联批次的PENDING通知,避免永久重复扫描。
     *
     * @param notices  待发送通知
     * @param batchMap 已加载的批次索引
     */
    private void markMissingBatchNoticesFailed(List<TornStockNoticeAuditDO> notices,
                                               Map<Long, TornStockVirtualBatchDO> batchMap) {
        List<Long> missingNoticeIds = notices.stream()
                .filter(notice -> notice.getBatchId() == null
                        || !batchMap.containsKey(notice.getBatchId()))
                .map(TornStockNoticeAuditDO::getId)
                .filter(Objects::nonNull)
                .toList();
        if (!missingNoticeIds.isEmpty()) {
            noticeAuditDao.markFailedByIds(missingNoticeIds, MISSING_BATCH_FAILURE_MESSAGE);
            log.warn("股票通知发送-无关联批次通知已标记FAILED: count={}", missingNoticeIds.size());
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
