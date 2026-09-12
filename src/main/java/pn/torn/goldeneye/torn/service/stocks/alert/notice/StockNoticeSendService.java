package pn.torn.goldeneye.torn.service.stocks.alert.notice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockAlphaRebalanceLegEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeTypeEnum;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgHttpBuilder;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.utils.JsonUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 股票通知发送服务 - 事务提交后查询PENDING通知、组合中文消息并调用Bot发送
 * <p>
 * 在VIP股票策略轮次事务提交后驱动消息投递。整体流程:
 * <ol>
 *   <li>校验 {@link SettingConstants#KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED} 开关(值为"true"时启用)</li>
 *   <li>查询全部PENDING通知 {@link TornStockNoticeAuditDAO#selectPendingNotices()}</li>
 *   <li>批量查询关联批次信息(用通知的batchId集合)</li>
 *   <li>α换仓通知以payload固化的{@code rebalanceAssociationId}为唯一键读取关联组完整通知集合,
 *       校验恰好一条SELL腿(legOrder=1)与一条BUY腿(legOrder=2)且换仓关联字段一致后,
 *       两腿合并为一条原子换仓消息统一冻结并只调用Bot一次;缺腿、重复腿、字段冲突、格式非法或
 *       一腿已进入终态时fail-closed,不调用Bot并把剩余PENDING腿标记为需人工核验的FAILED</li>
 *   <li>普通通知调用 {@link StockNoticeComposeService#composeAndMergeNotices} 组合未冻结通知;
 *       已冻结通知(快照已有messageText与frozenAt)按原冻结文本直接投递,不再重新组合</li>
 *   <li>未冻结通知先逐条冻结最终payload并校验更新行数,再调用Bot发送;
 *       已冻结通知不调用冻结更新,直接发送,仅按发送结果更新SENT/FAILED</li>
 *   <li>逐条构建 {@link GroupMsgHttpBuilder} + {@link TextQqMsg} 发送,HTTP 2xx且body非空时更新SENT,
 *       异常、非2xx、body为空或NapCat业务失败更新FAILED</li>
 *   <li>本期不自动重试,sendAttemptCount从0改为1</li>
 * </ol>
 * 单条通知发送异常不会中断后续通知投递,异常信息写入errorMessage字段。
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
     * Bot发送失败时的统一失败原因。
     */
    private static final String BOT_SEND_FAILURE_MESSAGE = "Bot返回null响应";
    /**
     * α换仓关联组必须包含的腿数(一条SELL腿 + 一条BUY腿)。
     */
    private static final int REBALANCE_LEG_COUNT = 2;
    /**
     * α换仓关联组必须两腿一致的换仓关联字段。
     */
    private static final List<String> REBALANCE_ASSOCIATION_FIELDS =
            List.of("rebalanceDecisionId", "originalBatchId", "replacementBatchId");
    /**
     * α换仓关联组异常的统一失败原因前缀,用于人工核验路径。
     */
    private static final String REBALANCE_GROUP_ANOMALY_PREFIX = "α换仓关联组异常,禁止发送需人工核验: ";

    private final Bot bot;
    private final ProjectProperty projectProperty;
    private final SysSettingManager sysSettingManager;
    private final TornStockNoticeAuditDAO noticeAuditDao;
    private final TornStockVirtualBatchDAO virtualBatchDao;
    private final StockNoticeComposeService stockNoticeComposeService;

    /**
     * 发送全部待发送(PENDING)通知
     * <p>
     * 事务提交后调用。执行流程:
     * <ol>
     *   <li>校验 {@link SettingConstants#KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED} 开关,非"true"直接返回</li>
     *   <li>查询全部PENDING通知,无记录直接返回</li>
     *   <li>批量查询关联批次信息(用通知的batchId集合),无有效批次的通知标记FAILED</li>
     *   <li>按{@code rebalanceAssociationId}分流: α换仓通知读取关联组完整两腿后作为一条原子消息统一冻结并只调用Bot一次;
     *       其余通知调用 {@link StockNoticeComposeService#composeAndMergeNotices} 组合并拆分消息</li>
     *   <li>发送前逐条冻结最终payload并校验更新行数,行数不符时不调用Bot</li>
     *   <li>逐条发送: HTTP 2xx且body非空时同组通知一并更新SENT;异常、非2xx或body为null同组通知一并更新FAILED并记录errorMessage</li>
     * </ol>
     * α换仓关联组缺腿、重复腿、字段冲突、格式非法或部分完成时fail-closed,不调用Bot并记录人工核验原因;
     * 单条通知发送异常不中断后续投递,整个方法不抛出异常(内部捕获并记录)。
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

        SendCounter counter = new SendCounter();
        PendingPartition partition = partitionPendingNotices(validNotices, counter);
        // α换仓关联组必须以完整两腿组合成一条原子消息发送,不得与普通通知合并后被拆分
        partition.rebalanceGroups().forEach((associationId, pendingLegs) ->
                sendRebalanceGroup(associationId, pendingLegs, counter));
        sendNormalNotices(partition.normalNotices(), batchMap, counter);

        log.info("股票通知发送-完成, 成功={}条, 失败={}条", counter.successCount, counter.failedCount);
    }

    /**
     * 将本轮有效PENDING通知按“是否属于α换仓关联组”分流。
     * <p>
     * payload固化{@code rebalanceAssociationId}的通知属于同一α换仓关联组,必须按关联标识成组处理;
     * 其余通知走普通组合链。通知类型为α换仓但缺少关联标识属于格式非法,直接fail-closed标记FAILED,
     * 禁止调用Bot,也不得当作单腿普通消息发送。
     *
     * @param validNotices 本轮有效PENDING通知
     * @param counter      发送计数
     * @return α换仓关联组分流结果与普通通知列表
     */
    private PendingPartition partitionPendingNotices(List<TornStockNoticeAuditDO> validNotices,
                                                     SendCounter counter) {
        Map<String, List<TornStockNoticeAuditDO>> rebalanceGroups = new LinkedHashMap<>();
        List<TornStockNoticeAuditDO> normalNotices = new ArrayList<>();
        for (TornStockNoticeAuditDO notice : validNotices) {
            String associationId = resolveRebalanceAssociationId(notice);
            if (associationId != null) {
                rebalanceGroups.computeIfAbsent(associationId, ignored -> new ArrayList<>(REBALANCE_LEG_COUNT))
                        .add(notice);
                continue;
            }
            if (isAlphaRebalanceNotice(notice)) {
                markRebalanceGroupAnomaly(List.of(notice), "通知类型为α换仓但缺少换仓关联标识");
                counter.failedCount++;
                continue;
            }
            normalNotices.add(notice);
        }
        return new PendingPartition(rebalanceGroups, normalNotices);
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
                                   SendCounter counter) {
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
                .filter(this::isAlreadyFrozen)
                .map(TornStockNoticeAuditDO::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        sendComposedMessages(composedMessages, noticeById, frozenNoticeIds, counter);
    }

    /**
     * 逐条发送已组合的普通消息并回写单次发送终态。
     *
     * @param composedMessages 已组合消息
     * @param noticeById       通知ID索引
     * @param frozenNoticeIds  已冻结通知ID集合
     * @param counter          发送计数
     */
    private void sendComposedMessages(List<StockNoticeComposeService.ComposedMessage> composedMessages,
                                      Map<Long, TornStockNoticeAuditDO> noticeById,
                                      Set<Long> frozenNoticeIds,
                                      SendCounter counter) {
        for (StockNoticeComposeService.ComposedMessage composedMessage : composedMessages) {
            // 已冻结通知重启投递不得再次冻结,避免覆盖首次冻结的payloadSnapshot/payloadHash/frozenAt/attemptedAt
            boolean allAlreadyFrozen = !composedMessage.noticeIds().isEmpty()
                    && frozenNoticeIds.containsAll(composedMessage.noticeIds());
            if (!allAlreadyFrozen) {
                LocalDateTime attemptedAt = LocalDateTime.now();
                if (!finalizePayload(noticeById, composedMessage.noticeIds(), composedMessage.text(), attemptedAt)) {
                    counter.failedCount++;
                    log.error("股票通知发送-最终payload冻结行数不符,停止发送本条合并消息: noticeCount={}",
                            composedMessage.noticeIds().size());
                    continue;
                }
            }
            SendResult sendResult = sendMessage(composedMessage.text());
            if (sendResult.success()) {
                counter.successCount++;
                markNoticesSent(composedMessage.noticeIds());
            } else {
                counter.failedCount++;
                markNoticesFailed(composedMessage.noticeIds(), sendResult.failureReason());
            }
        }
    }

    /**
     * 发送一次完整的α换仓关联组。
     * <p>
     * 关联组以通知payload固化的{@code rebalanceAssociationId}为唯一键,必须读取关联组完整通知集合
     * (不限发送状态):只依赖当前内存中的PENDING子集无法判断缺腿、重复腿与部分完成。校验通过后:
     * <ul>
     *   <li>两腿均为PENDING: 统一组合为一条α换仓消息,两腿payload同时冻结(更新行数必须为2),只调用Bot一次</li>
     *   <li>一腿已冻结、一腿未冻结: 复用已冻结文本并只补齐未冻结腿,不得覆盖已冻结腿的
     *       messageText/frozenAt/payloadHash;无法恢复完整一致消息时fail-closed</li>
     *   <li>一腿已SENT/FAILED: 识别为部分完成,不再发送已终态腿,剩余PENDING腿标记为需人工核验的FAILED</li>
     * </ul>
     * Bot成功后两腿同时更新为SENT,失败后两腿同时更新为FAILED,禁止把单腿结果解释为完整换仓通知送达。
     *
     * @param associationId 换仓关联标识
     * @param pendingLegs   本轮查询命中的该关联组PENDING腿
     * @param counter       发送计数
     */
    private void sendRebalanceGroup(String associationId, List<TornStockNoticeAuditDO> pendingLegs,
                                    SendCounter counter) {
        List<TornStockNoticeAuditDO> group = noticeAuditDao.selectByRebalanceAssociationId(associationId);
        RebalanceGroup rebalanceGroup = validateRebalanceGroup(associationId, group);
        if (!rebalanceGroup.valid()) {
            markRebalanceGroupAnomaly(pendingLegs, rebalanceGroup.reason());
            counter.failedCount++;
            return;
        }
        TornStockNoticeAuditDO sellLeg = rebalanceGroup.sellLeg();
        TornStockNoticeAuditDO buyLeg = rebalanceGroup.buyLeg();
        if (!isPendingNotice(sellLeg) || !isPendingNotice(buyLeg)) {
            markRebalanceGroupAnomaly(pendingLegs,
                    "关联组部分完成,一腿已进入终态,禁止重复发送或静默丢弃: sellStatus="
                            + sellLeg.getSendStatus() + ", buyStatus=" + buyLeg.getSendStatus());
            counter.failedCount++;
            return;
        }
        ResolvedRebalanceMessage resolved = resolveRebalanceMessage(sellLeg, buyLeg);
        if (resolved == null) {
            markRebalanceGroupAnomaly(pendingLegs, "无法恢复完整且一致的换仓消息");
            counter.failedCount++;
            return;
        }
        if (!finalizeRebalanceGroup(sellLeg, buyLeg, resolved)) {
            counter.failedCount++;
            log.error("股票通知发送-α换仓关联组冻结行数不符,停止发送: associationId={}", associationId);
            return;
        }
        List<Long> legIds = List.of(sellLeg.getId(), buyLeg.getId());
        SendResult sendResult = sendMessage(resolved.messageText());
        if (sendResult.success()) {
            counter.successCount++;
            markNoticesSent(legIds);
        } else {
            counter.failedCount++;
            markNoticesFailed(legIds, sendResult.failureReason());
        }
    }

    /**
     * 校验关联组恰好为一条SELL腿(legOrder=1)与一条BUY腿(legOrder=2)。
     * <p>
     * 通知数不为2、通知类型非α换仓、腿标识或腿顺序非法、缺腿、重复同类腿、关联标识不一致、
     * 换仓关联字段缺失或冲突、腿与关联批次绑定不一致时全部判定为非法关联组,调用方必须fail-closed。
     *
     * @param associationId 换仓关联标识
     * @param group         关联组完整通知集合
     * @return 校验结果
     */
    private RebalanceGroup validateRebalanceGroup(String associationId, List<TornStockNoticeAuditDO> group) {
        if (CollectionUtils.isEmpty(group)) {
            return RebalanceGroup.invalid("关联组不存在任何通知");
        }
        if (group.size() != REBALANCE_LEG_COUNT) {
            return RebalanceGroup.invalid("关联组通知数不为2: " + group.size());
        }
        TornStockNoticeAuditDO sellLeg = null;
        TornStockNoticeAuditDO buyLeg = null;
        for (TornStockNoticeAuditDO leg : group) {
            if (!isAlphaRebalanceNotice(leg)) {
                return RebalanceGroup.invalid("关联组通知类型非法: " + leg.getNoticeType());
            }
            String legCode = readPayloadText(leg, "rebalanceLeg");
            Integer legOrder = readPayloadInt(leg, "legOrder");
            if (StockAlphaRebalanceLegEnum.SELL.getCode().equals(legCode)
                    && Integer.valueOf(StockAlphaRebalanceLegEnum.SELL.getLegOrder()).equals(legOrder)) {
                if (sellLeg != null) {
                    return RebalanceGroup.invalid("关联组出现重复SELL腿");
                }
                sellLeg = leg;
                continue;
            }
            if (StockAlphaRebalanceLegEnum.BUY.getCode().equals(legCode)
                    && Integer.valueOf(StockAlphaRebalanceLegEnum.BUY.getLegOrder()).equals(legOrder)) {
                if (buyLeg != null) {
                    return RebalanceGroup.invalid("关联组出现重复BUY腿");
                }
                buyLeg = leg;
                continue;
            }
            return RebalanceGroup.invalid("关联组腿标识或腿顺序非法: leg=" + legCode + ", legOrder=" + legOrder);
        }
        if (sellLeg == null) {
            return RebalanceGroup.invalid("关联组缺少SELL腿");
        }
        if (buyLeg == null) {
            return RebalanceGroup.invalid("关联组缺少BUY腿");
        }
        if (sellLeg.getId() == null || buyLeg.getId() == null) {
            return RebalanceGroup.invalid("关联组通知缺少主键");
        }
        String conflictReason = resolveAssociationConflictReason(associationId, sellLeg, buyLeg);
        if (conflictReason != null) {
            return RebalanceGroup.invalid(conflictReason);
        }
        return RebalanceGroup.valid(sellLeg, buyLeg);
    }

    /**
     * 校验关联标识一致、换仓关联字段完整一致且腿与关联批次绑定一致。
     *
     * @param associationId 换仓关联标识
     * @param sellLeg       原仓卖出腿
     * @param buyLeg        新仓买入腿
     * @return 冲突原因;完全一致时返回null
     */
    private String resolveAssociationConflictReason(String associationId, TornStockNoticeAuditDO sellLeg,
                                                    TornStockNoticeAuditDO buyLeg) {
        if (!associationId.equals(readPayloadText(sellLeg, "rebalanceAssociationId"))
                || !associationId.equals(readPayloadText(buyLeg, "rebalanceAssociationId"))) {
            return "关联组换仓关联标识不一致";
        }
        for (String field : REBALANCE_ASSOCIATION_FIELDS) {
            String sellValue = readPayloadText(sellLeg, field);
            String buyValue = readPayloadText(buyLeg, field);
            if (sellValue == null) {
                return "关联组换仓关联字段缺失: " + field;
            }
            if (!sellValue.equals(buyValue)) {
                return "关联组换仓关联字段冲突: " + field;
            }
        }
        if (!String.valueOf(sellLeg.getBatchId()).equals(readPayloadText(sellLeg, "originalBatchId"))
                || !String.valueOf(buyLeg.getBatchId()).equals(readPayloadText(buyLeg, "replacementBatchId"))) {
            return "关联组腿与关联批次绑定不一致";
        }
        return null;
    }

    /**
     * 解析本次发送的最终换仓消息文本。
     * <p>
     * 两腿均未冻结时按当前两腿重新组合;两腿均已冻结时复用同一冻结文本,文本不一致即判定无法恢复;
     * 一腿已冻结、一腿未冻结时,已冻结文本必须与当前两腿可组合文本完全一致才可用于补齐另一腿,
     * 否则fail-closed,禁止把单腿文本当成完整换仓消息。
     *
     * @param sellLeg 原仓卖出腿
     * @param buyLeg  新仓买入腿
     * @return 最终消息上下文;无法恢复完整一致消息时返回null
     */
    private ResolvedRebalanceMessage resolveRebalanceMessage(TornStockNoticeAuditDO sellLeg,
                                                             TornStockNoticeAuditDO buyLeg) {
        boolean sellFrozen = isAlreadyFrozen(sellLeg);
        boolean buyFrozen = isAlreadyFrozen(buyLeg);
        if (sellFrozen && buyFrozen) {
            String sellText = extractFrozenMessageText(sellLeg.getPayloadSnapshot());
            String buyText = extractFrozenMessageText(buyLeg.getPayloadSnapshot());
            if (sellText == null || !sellText.equals(buyText)) {
                log.error("股票通知发送-α换仓两腿已冻结文本不一致,无法恢复完整换仓消息: sellNoticeId={}, buyNoticeId={}",
                        sellLeg.getId(), buyLeg.getId());
                return null;
            }
            return new ResolvedRebalanceMessage(sellText, null);
        }
        String composedText = composeRebalanceGroupText(sellLeg, buyLeg);
        if (!sellFrozen && !buyFrozen) {
            return composedText == null ? null : new ResolvedRebalanceMessage(composedText, null);
        }
        TornStockNoticeAuditDO frozenLeg = sellFrozen ? sellLeg : buyLeg;
        String frozenText = extractFrozenMessageText(frozenLeg.getPayloadSnapshot());
        LocalDateTime frozenAt = extractFrozenAt(frozenLeg.getPayloadSnapshot());
        if (frozenText == null || frozenAt == null || !frozenText.equals(composedText)) {
            log.error("股票通知发送-α换仓一腿已冻结但无法恢复完整一致换仓消息,禁止发送: associationId={}, frozenNoticeId={}",
                    readPayloadText(sellLeg, "rebalanceAssociationId"), frozenLeg.getId());
            return null;
        }
        return new ResolvedRebalanceMessage(frozenText, frozenAt);
    }

    /**
     * 将关联组两腿组合为一条α换仓消息,并要求组合结果完整包含两腿。
     *
     * @param sellLeg 原仓卖出腿
     * @param buyLeg  新仓买入腿
     * @return 组合后的换仓消息文本;无法组合出完整两腿消息时返回null
     */
    private String composeRebalanceGroupText(TornStockNoticeAuditDO sellLeg, TornStockNoticeAuditDO buyLeg) {
        Map<Long, TornStockVirtualBatchDO> batchMap = loadBatchMap(List.of(sellLeg, buyLeg));
        List<StockNoticeComposeService.ComposedMessage> messages =
                stockNoticeComposeService.composeAndMergeNotices(List.of(sellLeg, buyLeg), batchMap);
        if (messages == null || messages.size() != 1) {
            return null;
        }
        StockNoticeComposeService.ComposedMessage message = messages.getFirst();
        if (message.text() == null || message.text().isBlank()) {
            return null;
        }
        if (!new HashSet<>(message.noticeIds()).equals(Set.of(sellLeg.getId(), buyLeg.getId()))) {
            return null;
        }
        return message.text();
    }

    /**
     * 冻结关联组尚未冻结的腿,并校验冻结更新行数等于待冻结腿数。
     * <p>
     * 两腿均已冻结时不做任何冻结更新,直接复用已冻结payload(重启恢复不得覆盖
     * messageText/frozenAt/payloadHash);部分冻结时只补齐未冻结腿,并沿用已冻结腿的冻结时间
     * 保证两腿处于同一最终消息上下文。
     *
     * @param sellLeg  原仓卖出腿
     * @param buyLeg   新仓买入腿
     * @param resolved 已解析的最终消息上下文
     * @return 冻结成功返回true;行数不符返回false
     */
    private boolean finalizeRebalanceGroup(TornStockNoticeAuditDO sellLeg, TornStockNoticeAuditDO buyLeg,
                                           ResolvedRebalanceMessage resolved) {
        List<Long> unfrozenLegIds = new ArrayList<>(REBALANCE_LEG_COUNT);
        if (!isAlreadyFrozen(sellLeg)) {
            unfrozenLegIds.add(sellLeg.getId());
        }
        if (!isAlreadyFrozen(buyLeg)) {
            unfrozenLegIds.add(buyLeg.getId());
        }
        if (unfrozenLegIds.isEmpty()) {
            return true;
        }
        LocalDateTime attemptedAt = resolved.frozenAt() != null ? resolved.frozenAt() : LocalDateTime.now();
        Map<Long, TornStockNoticeAuditDO> legById = indexNoticesById(List.of(sellLeg, buyLeg));
        return finalizePayload(legById, unfrozenLegIds, resolved.messageText(), attemptedAt);
    }

    /**
     * 记录α换仓关联组异常并将剩余PENDING腿标记为需人工核验的FAILED终态。
     * <p>
     * 关联组缺腿、重复腿、字段冲突、格式非法或部分完成时禁止调用Bot;标记FAILED是本批次明确的
     * fail-closed人工处理规则,既不静默重复发送也不静默丢弃,并保留统一关联事实供人工核验。
     *
     * @param pendingLegs 本轮仍为PENDING的关联组腿
     * @param reason      异常原因
     */
    private void markRebalanceGroupAnomaly(List<TornStockNoticeAuditDO> pendingLegs, String reason) {
        List<Long> noticeIds = pendingLegs == null ? List.of() : pendingLegs.stream()
                .map(TornStockNoticeAuditDO::getId)
                .filter(Objects::nonNull)
                .toList();
        log.error("股票通知发送-α换仓关联组异常,禁止调用Bot: reason={}, noticeIds={}", reason, noticeIds);
        if (noticeIds.isEmpty()) {
            return;
        }
        try {
            int updated = noticeAuditDao.markFailedByIds(noticeIds, REBALANCE_GROUP_ANOMALY_PREFIX + reason);
            if (updated != noticeIds.size()) {
                log.error("股票通知发送-α换仓关联组异常标记行数不完整: updated={}, expected={}",
                        updated, noticeIds.size());
            }
        } catch (Exception e) {
            log.error("股票通知发送-α换仓关联组异常标记失败, noticeCount={}", noticeIds.size(), e);
        }
    }

    /**
     * 判断通知是否为α换仓通知类型。
     *
     * @param notice 通知审计
     * @return α换仓通知返回true
     */
    private boolean isAlphaRebalanceNotice(TornStockNoticeAuditDO notice) {
        return notice != null && StockNoticeTypeEnum.ALPHA_REBALANCE.getCode().equals(notice.getNoticeType());
    }

    /**
     * 判断通知是否仍处于待发送PENDING状态。
     *
     * @param notice 通知审计
     * @return PENDING返回true
     */
    private boolean isPendingNotice(TornStockNoticeAuditDO notice) {
        return notice != null && StockNoticeStatusEnum.PENDING.getCode().equals(notice.getSendStatus());
    }

    /**
     * 读取通知payload中固化的α换仓关联标识。
     *
     * @param notice 通知审计
     * @return 换仓关联标识;不存在时返回null
     */
    private String resolveRebalanceAssociationId(TornStockNoticeAuditDO notice) {
        return notice == null ? null : readJsonText(notice.getPayloadSnapshot(), "rebalanceAssociationId");
    }

    /**
     * 读取通知payload中的文本字段。
     *
     * @param notice 通知审计
     * @param field  字段名
     * @return 字段文本;不存在或为空时返回null
     */
    private String readPayloadText(TornStockNoticeAuditDO notice, String field) {
        return notice == null ? null : readJsonText(notice.getPayloadSnapshot(), field);
    }

    /**
     * 读取payloadJSON中的文本字段。
     *
     * @param payloadSnapshot 通知载荷JSON
     * @param field           字段名
     * @return 字段文本;不存在或为空时返回null
     */
    private String readJsonText(String payloadSnapshot, String field) {
        if (payloadSnapshot == null || payloadSnapshot.isBlank()) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode node = JsonUtils.getNode(payloadSnapshot, field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 读取payloadJSON中的整数字段。
     *
     * @param notice 通知审计
     * @param field  字段名
     * @return 字段整数;不存在时返回null
     */
    private Integer readPayloadInt(TornStockNoticeAuditDO notice, String field) {
        if (notice == null || notice.getPayloadSnapshot() == null || notice.getPayloadSnapshot().isBlank()) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode node = JsonUtils.getNode(notice.getPayloadSnapshot(), field);
        return node == null || node.isNull() ? null : node.asInt();
    }

    /**
     * 读取已冻结腿的冻结时间。
     *
     * @param payloadSnapshot 通知载荷JSON
     * @return 冻结时间;缺失或无法解析时返回null
     */
    private LocalDateTime extractFrozenAt(String payloadSnapshot) {
        String frozenAtText = readJsonText(payloadSnapshot, "frozenAt");
        if (frozenAtText == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(frozenAtText);
        } catch (DateTimeParseException e) {
            log.error("股票通知发送-α换仓已冻结腿frozenAt无法解析: frozenAt={}", frozenAtText);
            return null;
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
            if (!isAlreadyFrozen(notice)) {
                noticesToCompose.add(notice);
            } else {
                String frozenText = extractFrozenMessageText(notice.getPayloadSnapshot());
                frozenNoticeIdsByText.computeIfAbsent(frozenText, ignored -> new ArrayList<>())
                        .add(notice.getId());
            }
        }

        List<StockNoticeComposeService.ComposedMessage> messages = new ArrayList<>();
        frozenNoticeIdsByText.forEach((text, noticeIds) ->
                messages.add(new StockNoticeComposeService.ComposedMessage(noticeIds, text)));
        messages.addAll(stockNoticeComposeService.composeAndMergeNotices(noticesToCompose, batchMap));
        return messages;
    }

    /**
     * 判断通知是否已完成首次payload冻结。
     * <p>
     * 冻结完成的标志是payload快照同时存在有效{@code messageText}与{@code frozenAt}。
     * 仅存在messageText而缺少frozenAt时不算已冻结,必须重新冻结。
     *
     * @param notice 通知审计DO
     * @return 已冻结返回true;否则false
     */
    private boolean isAlreadyFrozen(TornStockNoticeAuditDO notice) {
        if (notice == null || notice.getPayloadSnapshot() == null || notice.getPayloadSnapshot().isBlank()) {
            return false;
        }
        com.fasterxml.jackson.databind.JsonNode textNode =
                JsonUtils.getNode(notice.getPayloadSnapshot(), "messageText");
        if (textNode == null || textNode.isNull() || textNode.asText().isBlank()) {
            return false;
        }
        com.fasterxml.jackson.databind.JsonNode frozenAtNode =
                JsonUtils.getNode(notice.getPayloadSnapshot(), "frozenAt");
        return frozenAtNode != null && !frozenAtNode.isNull() && !frozenAtNode.asText().isBlank();
    }

    /**
     * 从通知载荷中读取已冻结的最终文本。
     *
     * @param payloadSnapshot 通知载荷JSON
     * @return 冻结文本；不存在时返回null
     */
    private String extractFrozenMessageText(String payloadSnapshot) {
        if (payloadSnapshot == null || payloadSnapshot.isBlank()) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode messageNode =
                JsonUtils.getNode(payloadSnapshot, "messageText");
        if (messageNode == null || messageNode.isNull()) {
            return null;
        }
        return messageNode.asText();
    }

    /**
     * 在发送前逐条冻结最终消息载荷。
     * <p>
     * 对每条通知读取创建时业务payload,合并最终{@code messageText}与{@code frozenAt},
     * 保留全部业务字段(如formalReason/originalExitReason/recoveryBar等),不得覆盖。
     * 最终payload经 {@link StockNoticePayloadCanonicalizer} 规范化后计算哈希。
     * 冻结UPDATE行数必须等于通知数,否则返回false并停止发送本条合并消息,禁止发送不可审计消息。
     *
     * @param noticeById  通知ID索引
     * @param noticeIds   本合并消息通知ID列表
     * @param messageText 最终消息文本
     * @param attemptedAt 实际发送尝试时间
     * @return 冻结成功(更新行数等于通知数)返回true;否则false
     */
    private boolean finalizePayload(Map<Long, TornStockNoticeAuditDO> noticeById,
                                    List<Long> noticeIds,
                                    String messageText,
                                    LocalDateTime attemptedAt) {
        if (noticeIds == null || noticeIds.isEmpty()) {
            return true;
        }
        List<NoticePayloadFinalizeCommand> commands = new ArrayList<>();
        for (Long noticeId : noticeIds) {
            TornStockNoticeAuditDO notice = noticeById.get(noticeId);
            if (notice == null) {
                log.error("股票通知发送-通知不存在,无法冻结payload: noticeId={}", noticeId);
                return false;
            }
            String originalPayload = notice.getPayloadSnapshot();
            String finalPayload = StockNoticePayloadCanonicalizer.mergeAndCanonicalize(
                    originalPayload, messageText, attemptedAt);
            commands.add(new NoticePayloadFinalizeCommand(
                    noticeId, finalPayload, StockNoticePayloadCanonicalizer.sha256(finalPayload), attemptedAt));
        }
        int updated = noticeAuditDao.finalizePayload(commands);
        if (updated != noticeIds.size()) {
            log.error("股票通知发送-最终payload冻结行数不符: updated={}, expected={}",
                    updated, noticeIds.size());
            return false;
        }
        return true;
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

    /**
     * 发送单条群消息
     * <p>
     * 构建群消息请求(目标群为 {@link ProjectProperty#getVipGroupId()}),添加文本消息,
     * 调用 {@link Bot#sendRequest} 发送。返回是否发送成功。
     * <p>
     * 成功判定须同时满足以下条件,任一不满足即视为失败并返回false:
     * <ol>
     *   <li>{@link ResponseEntity} 非null</li>
     *   <li>HTTP状态码为2xx({@link org.springframework.http.HttpStatusCode#is2xxSuccessful()})</li>
     *   <li>响应body非null</li>
     *   <li>NapCat业务结果retcode == 0(解析body JSON中的retcode字段)</li>
     * </ol>
     * 发送过程中抛出的任何异常都会被捕获并记录日志,方法返回false,不向上抛出。
     *
     * @param text 待发送的中文消息文本
     * @return true表示发送成功(2xx且body非空且retcode=0);false表示发送失败、响应异常或无法确认成功
     */
    public boolean sendSingleMessage(String text) {
        return sendMessage(text).success();
    }

    /**
     * 发送单条消息并返回可审计的失败原因。
     *
     * @param text 待发送文本
     * @return 发送结果
     */
    private SendResult sendMessage(String text) {
        try {
            BotHttpReqParam param = new GroupMsgHttpBuilder()
                    .setGroupId(projectProperty.getVipGroupId())
                    .addMsg(new TextQqMsg(text))
                    .build();
            ResponseEntity<String> response = bot.sendRequest(param, String.class);
            if (response == null) {
                log.warn("股票通知发送-Bot返回null响应, 发送失败");
                return SendResult.failure(BOT_SEND_FAILURE_MESSAGE);
            }
            if (!response.getStatusCode().is2xxSuccessful()) {
                log.warn("股票通知发送-HTTP状态非2xx, 发送失败, statusCode={}", response.getStatusCode());
                return SendResult.failure("HTTP状态非2xx: " + response.getStatusCode());
            }
            String body = response.getBody();
            if (body == null) {
                log.warn("股票通知发送-响应body为空, 无法确认发送成功");
                return SendResult.failure("响应body为空");
            }
            if (!isNapCatSuccess(body)) {
                log.warn("股票通知发送-NapCat业务结果非成功, body={}", body);
                return SendResult.failure("NapCat业务结果非成功");
            }
            return SendResult.successful();
        } catch (Exception e) {
            log.error("股票通知发送-单条消息发送异常", e);
            return SendResult.failure("发送异常: " + e.getClass().getSimpleName());
        }
    }

    /**
     * 解析NapCat响应body判断业务是否成功。
     * <p>
     * NapCat返回JSON格式: {@code {"status":"ok","retcode":0,"data":...}},
     * 当retcode为0且status为"ok"时视为业务成功,其他情况视为失败。
     * 使用项目统一的 {@link JsonUtils#getNode} 解析JSON,避免暴露内部ObjectMapper。
     *
     * @param body NapCat响应body文本
     * @return true表示retcode=0且status=ok;false表示业务失败或解析异常
     */
    private boolean isNapCatSuccess(String body) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = JsonUtils.getNode(body, "retcode");
            int retcode = root != null ? root.asInt(-1) : -1;
            com.fasterxml.jackson.databind.JsonNode statusNode = JsonUtils.getNode(body, "status");
            String status = statusNode != null ? statusNode.asText() : null;
            return retcode == 0 && "ok".equals(status);
        } catch (Exception e) {
            log.warn("股票通知发送-NapCat响应解析异常,视为失败: body={}", body, e);
            return false;
        }
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
            noticeAuditDao.markFailedByIds(missingNoticeIds, "关联虚拟交易批次不存在");
            log.warn("股票通知发送-无关联批次通知已标记FAILED: count={}", missingNoticeIds.size());
        }
    }

    /**
     * 将一批通知标记为已发送(SENT)并设置发送成功时间。
     * <p>
     * SENT为终态且不得再次发送。更新行数必须完整等于本条消息的通知数:行数不足说明存在
     * 一条通知已处于其他终态而另一条仍为PENDING的部分成功状态,必须记录ERROR以便审计发现,
     * 不得把单腿成功解释为完整换仓通知成功(发送已完成,不回滚交易事实)。
     *
     * @param noticeIds 本批次组合消息对应的通知ID列表
     */
    private void markNoticesSent(List<Long> noticeIds) {
        if (noticeIds == null || noticeIds.isEmpty()) {
            return;
        }
        try {
            int updated = noticeAuditDao.markSentByIds(noticeIds);
            if (updated != noticeIds.size()) {
                log.error("股票通知发送-标记SENT行数不完整,同组通知状态不一致: updated={}, expected={}",
                        updated, noticeIds.size());
            }
        } catch (Exception e) {
            log.error("股票通知发送-批量标记SENT状态异常, noticeCount={}", noticeIds.size(), e);
        }
    }

    /**
     * 将一批通知标记为发送失败(FAILED)并记录实际错误信息
     * <p>
     * FAILED为本批次终态,不被后续普通调度自动重新查询和发送。更新行数必须完整等于
     * 本条消息的通知数:行数不足说明同组通知未全部进入同一失败终态,必须记录ERROR。
     *
     * @param noticeIds     本批次组合消息对应的通知ID列表
     * @param failureReason 实际发送失败原因
     */
    private void markNoticesFailed(List<Long> noticeIds, String failureReason) {
        if (noticeIds == null || noticeIds.isEmpty()) {
            return;
        }
        try {
            int updated = noticeAuditDao.markSendFailedByIds(noticeIds,
                    failureReason == null || failureReason.isBlank()
                            ? BOT_SEND_FAILURE_MESSAGE : failureReason);
            if (updated != noticeIds.size()) {
                log.error("股票通知发送-标记FAILED行数不完整,同组通知状态不一致: updated={}, expected={}",
                        updated, noticeIds.size());
            }
        } catch (Exception e) {
            log.error("股票通知发送-批量标记FAILED状态异常, noticeCount={}", noticeIds.size(), e);
        }
    }

    /**
     * α换仓关联组校验结果。
     *
     * @param valid    是否通过校验
     * @param reason   校验失败原因;通过时为null
     * @param sellLeg  原仓卖出腿;校验失败时为null
     * @param buyLeg   新仓买入腿;校验失败时为null
     */
    private record RebalanceGroup(
            boolean valid,
            String reason,
            TornStockNoticeAuditDO sellLeg,
            TornStockNoticeAuditDO buyLeg) {
        private static RebalanceGroup valid(TornStockNoticeAuditDO sellLeg, TornStockNoticeAuditDO buyLeg) {
            return new RebalanceGroup(true, null, sellLeg, buyLeg);
        }

        private static RebalanceGroup invalid(String reason) {
            return new RebalanceGroup(false, reason, null, null);
        }
    }

    /**
     * α换仓关联组最终消息上下文。
     *
     * @param messageText 两腿共用的最终消息文本
     * @param frozenAt    已存在的冻结时间;仅部分冻结补齐时非null
     */
    private record ResolvedRebalanceMessage(
            String messageText,
            LocalDateTime frozenAt) {
    }

    /**
     * 本轮PENDING通知分流结果。
     *
     * @param rebalanceGroups 换仓关联标识到该关联组PENDING腿的映射
     * @param normalNotices   普通通知列表
     */
    private record PendingPartition(
            Map<String, List<TornStockNoticeAuditDO>> rebalanceGroups,
            List<TornStockNoticeAuditDO> normalNotices) {
    }

    /**
     * 本轮发送计数(按消息条数统计)。
     */
    private static final class SendCounter {
        /**
         * 发送成功条数
         */
        private int successCount;
        /**
         * 发送失败或fail-closed条数
         */
        private int failedCount;
    }

    private record SendResult(
            boolean success,
            String failureReason) {
        private static SendResult successful() {
            return new SendResult(true, null);
        }

        private static SendResult failure(String reason) {
            return new SendResult(false, reason);
        }
    }
}
