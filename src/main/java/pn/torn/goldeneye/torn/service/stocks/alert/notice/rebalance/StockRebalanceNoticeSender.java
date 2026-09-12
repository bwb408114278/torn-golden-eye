package pn.torn.goldeneye.torn.service.stocks.alert.notice.rebalance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockAlphaRebalanceLegEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeTypeEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * α换仓关联组通知发送器 - 以{@code rebalanceAssociationId}为唯一键完成换仓两腿的原子发送闭包。
 * <p>
 * α换仓的两条通知审计共享同一换仓关联标识,必须作为一条消息整体发送:任何时刻都不得只发送一条腿,
 * 也不得把单腿发送结果解释为完整换仓通知送达。本发送器负责:
 * <ol>
 *   <li>按关联标识分组:关联标识缺失的α换仓通知属于格式非法,直接fail-closed,不调用Bot,
 *       也不得当作单腿普通消息发送</li>
 *   <li>按关联标识读取关联组完整通知集合(不限发送状态):只依赖当前内存中的可发送子集
 *       无法判断缺腿、重复腿与部分完成</li>
 *   <li>校验恰好一条SELL腿(legOrder=1)与一条BUY腿(legOrder=2),且通知类型、腿标识、腿顺序、
 *       换仓关联字段与关联批次绑定全部一致</li>
 *   <li>以换仓关联标识为单位原子领取两腿并累计一次尝试,领取行数不足时释放领取且不调用Bot</li>
 *   <li>两腿均未冻结时组合为一条原子换仓消息统一冻结(更新行数必须为2)并只调用Bot一次;
 *       一腿已冻结、一腿未冻结时只补齐未冻结腿并沿用已冻结腿的冻结时间,不覆盖已冻结腿的
 *       messageText/frozenAt/payloadHash;两腿均已冻结(自动重发)时复用同一冻结文本,
 *       不重复冻结、不重新组合</li>
 *   <li>两腿在本次领取中同时回写终态:成功为SENT,失败未达3次总尝试上限为FAILED_RETRYABLE
 *       由后续调度自动重发,达到上限为FAILED_FINAL</li>
 * </ol>
 * 缺腿、重复同类腿、字段冲突、格式非法或状态不一致时fail-closed:禁止调用Bot,剩余可发送腿置为
 * 人工核验的FAILED_FINAL终态,既不静默重复发送也不静默丢弃,并保留统一关联事实供人工核验。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockRebalanceNoticeSender {
    /**
     * α换仓关联组必须包含的腿数(一条SELL腿 + 一条BUY腿)。
     */
    private static final int REBALANCE_LEG_COUNT = 2;
    /**
     * α换仓关联标识字段名。
     */
    private static final String FIELD_REBALANCE_ASSOCIATION_ID = "rebalanceAssociationId";
    /**
     * α换仓决策ID字段名。
     */
    private static final String FIELD_REBALANCE_DECISION_ID = "rebalanceDecisionId";
    /**
     * 原仓批次ID字段名。
     */
    private static final String FIELD_ORIGINAL_BATCH_ID = "originalBatchId";
    /**
     * 新仓批次ID字段名。
     */
    private static final String FIELD_REPLACEMENT_BATCH_ID = "replacementBatchId";
    /**
     * 换仓腿标识字段名。
     */
    private static final String FIELD_REBALANCE_LEG = "rebalanceLeg";
    /**
     * 换仓腿顺序字段名。
     */
    private static final String FIELD_LEG_ORDER = "legOrder";
    /**
     * α换仓关联组必须两腿一致的换仓关联字段。
     */
    private static final List<String> REBALANCE_ASSOCIATION_FIELDS =
            List.of(FIELD_REBALANCE_DECISION_ID, FIELD_ORIGINAL_BATCH_ID, FIELD_REPLACEMENT_BATCH_ID);
    /**
     * α换仓关联组异常的统一失败原因前缀,用于人工核验路径。
     */
    private static final String REBALANCE_GROUP_ANOMALY_PREFIX = "α换仓关联组异常,禁止发送需人工核验: ";
    /**
     * α换仓通知缺少换仓关联标识的异常原因。
     */
    private static final String MISSING_ASSOCIATION_ID_REASON = "通知类型为α换仓但缺少换仓关联标识";
    /**
     * 关联组无法恢复完整一致换仓消息的异常原因。
     */
    private static final String UNRECOVERABLE_MESSAGE_REASON = "无法恢复完整且一致的换仓消息";
    /**
     * 关联组冻结行数不符时的失败原因。
     */
    private static final String FINALIZE_FAILURE_MESSAGE = "α换仓关联组最终payload冻结行数不符";

    private final TornStockNoticeAuditDAO noticeAuditDao;
    private final StockNoticeComposeService stockNoticeComposeService;
    private final StockNoticeSendRecorder sendRecorder;
    private final StockNoticeBotSender botSender;

    /**
     * 判断通知是否为α换仓通知类型。
     * <p>
     * 只有α换仓通知参与换仓关联组闭包校验;其他通知类型不参与,也不得被换仓字段影响。
     *
     * @param notice 通知审计
     * @return α换仓通知返回true;通知为空或其他类型返回false
     */
    public static boolean isRebalanceNotice(TornStockNoticeAuditDO notice) {
        return notice != null && StockNoticeTypeEnum.ALPHA_REBALANCE.getCode().equals(notice.getNoticeType());
    }

    /**
     * 判断通知是否必须进入α换仓关联组闭包处理。
     * <p>
     * 通知类型为α换仓,或payload固化换仓关联标识的通知都必须成组处理,不得当作单腿普通消息发送:
     * 前者保证α换仓消息不会被拆成单腿;后者保证任何携带换仓关联事实的通知都必须通过完整两腿校验,
     * 缺腿、重复腿、字段冲突或通知类型非法时一律fail-closed。
     *
     * @param notice 通知审计
     * @return 必须按关联组处理返回true
     */
    public static boolean isRebalanceGroupMember(TornStockNoticeAuditDO notice) {
        return isRebalanceNotice(notice)
                || StockNoticePayloadReader.readText(notice, FIELD_REBALANCE_ASSOCIATION_ID) != null;
    }

    /**
     * 发送本轮全部α换仓关联组。
     *
     * @param rebalanceNotices 本轮可发送的α换仓通知
     * @param batchMap         批次ID到批次DO的映射,用于组合两腿消息
     * @param counter          发送计数
     */
    public void sendGroups(List<TornStockNoticeAuditDO> rebalanceNotices,
                           Map<Long, TornStockVirtualBatchDO> batchMap,
                           NoticeSendCounter counter) {
        if (CollectionUtils.isEmpty(rebalanceNotices)) {
            return;
        }
        RebalancePartition partition = partitionByAssociationId(rebalanceNotices);
        partition.malformedNotices().forEach(notice ->
                markGroupAnomaly(List.of(notice), MISSING_ASSOCIATION_ID_REASON, counter));
        partition.groups().forEach((associationId, pendingLegs) ->
                sendGroup(associationId, pendingLegs, batchMap, counter));
    }

    /**
     * 将本轮可发送的α换仓通知按换仓关联标识分组,并分离缺少关联标识的非法通知。
     *
     * @param rebalanceNotices 本轮可发送的α换仓通知
     * @return 关联标识到该组可发送腿的映射与非法通知列表
     */
    private RebalancePartition partitionByAssociationId(List<TornStockNoticeAuditDO> rebalanceNotices) {
        Map<String, List<TornStockNoticeAuditDO>> groups = new LinkedHashMap<>();
        List<TornStockNoticeAuditDO> malformedNotices = new ArrayList<>();
        for (TornStockNoticeAuditDO notice : rebalanceNotices) {
            String associationId = StockNoticePayloadReader.readText(notice, FIELD_REBALANCE_ASSOCIATION_ID);
            if (associationId == null) {
                malformedNotices.add(notice);
            } else {
                groups.computeIfAbsent(associationId, ignored -> new ArrayList<>(REBALANCE_LEG_COUNT)).add(notice);
            }
        }
        return new RebalancePartition(groups, malformedNotices);
    }

    /**
     * 领取并发送一个α换仓关联组。
     * <p>
     * 关联组校验、消息恢复均在领取前完成:任何fail-closed判定都不消耗领取,也不调用Bot;
     * 领取以关联标识为单位原子完成,只有本次实际领取的腿会参与冻结与终态回写,
     * 已SENT腿不会被重新发送。
     *
     * @param associationId 换仓关联标识
     * @param pendingLegs   本轮查询命中的该关联组可发送腿
     * @param batchMap      批次ID到批次DO的映射
     * @param counter       发送计数
     */
    private void sendGroup(String associationId, List<TornStockNoticeAuditDO> pendingLegs,
                           Map<Long, TornStockVirtualBatchDO> batchMap, NoticeSendCounter counter) {
        List<TornStockNoticeAuditDO> group = noticeAuditDao.selectByRebalanceAssociationId(associationId);
        RebalanceGroupValidation validation = validateGroup(associationId, group);
        if (!validation.passed()) {
            markGroupAnomaly(pendingLegs, validation.reason(), counter);
            return;
        }
        TornStockNoticeAuditDO sellLeg = validation.sellLeg();
        TornStockNoticeAuditDO buyLeg = validation.buyLeg();
        List<TornStockNoticeAuditDO> sendableLegs = resolveSendableLegs(sellLeg, buyLeg);
        if (sendableLegs.isEmpty()) {
            log.debug("股票通知发送-α换仓两腿均已进入终态,无需发送: associationId={}", associationId);
            return;
        }
        if (sendableLegs.size() < REBALANCE_LEG_COUNT) {
            markGroupAnomaly(sendableLegs, partialCompletionReason(sellLeg, buyLeg), counter);
            return;
        }
        RebalanceMessage message = resolveMessage(sellLeg, buyLeg, batchMap);
        if (message == null) {
            markGroupAnomaly(sendableLegs, UNRECOVERABLE_MESSAGE_REASON, counter);
            return;
        }
        List<Long> sendableLegIds = sendableLegs.stream().map(TornStockNoticeAuditDO::getId).toList();
        String claimToken = sendRecorder.newClaimToken();
        if (!sendRecorder.claimRebalanceGroup(associationId, sendableLegIds.size(), claimToken)) {
            counter.countFailure();
            log.warn("股票通知发送-α换仓关联组领取失败,跳过发送: associationId={}", associationId);
            return;
        }
        if (!freezeUnfrozenLegs(sellLeg, buyLeg, message, claimToken)) {
            counter.countFailure();
            log.error("股票通知发送-α换仓关联组冻结行数不符,停止发送: sellNoticeId={}, buyNoticeId={}",
                    sellLeg.getId(), buyLeg.getId());
            sendRecorder.markSendFailed(sendableLegIds, claimToken, FINALIZE_FAILURE_MESSAGE);
            return;
        }
        deliverGroupMessage(message, sendableLegIds, claimToken, counter);
    }

    /**
     * 调用Bot发送关联组消息并按结果回写本次实际领取腿的终态。
     * <p>
     * 两腿必须在同一次回写中进入同一状态:成功为SENT,失败未达总尝试上限为FAILED_RETRYABLE
     * (后续调度自动重发,重发复用首次冻结文本),达到上限为FAILED_FINAL。
     *
     * @param message    已冻结的换仓消息上下文
     * @param legIds     本次实际领取的腿ID(按SELL、BUY顺序)
     * @param claimToken 本次领取标识
     * @param counter    发送计数
     */
    private void deliverGroupMessage(RebalanceMessage message, List<Long> legIds, String claimToken,
                                     NoticeSendCounter counter) {
        StockNoticeBotSender.SendResult sendResult = botSender.send(message.messageText());
        if (sendResult.success()) {
            counter.countSuccess();
            sendRecorder.markSent(legIds, claimToken);
        } else {
            counter.countFailure();
            sendRecorder.markSendFailed(legIds, claimToken, sendResult.failureReason());
        }
    }

    /**
     * 解析本次仍可领取发送的腿。
     * <p>
     * 只有处于PENDING/FAILED_RETRYABLE且未达总尝试上限的腿可参与本次发送;已SENT腿与
     * 达到上限的FAILED_FINAL腿不会被重新发送。
     *
     * @param sellLeg 原仓卖出腿
     * @param buyLeg  新仓买入腿
     * @return 仍可发送的腿(顺序为SELL、BUY)
     */
    private List<TornStockNoticeAuditDO> resolveSendableLegs(TornStockNoticeAuditDO sellLeg,
                                                             TornStockNoticeAuditDO buyLeg) {
        List<TornStockNoticeAuditDO> sendableLegs = new ArrayList<>(REBALANCE_LEG_COUNT);
        if (isSendableNotice(sellLeg)) {
            sendableLegs.add(sellLeg);
        }
        if (isSendableNotice(buyLeg)) {
            sendableLegs.add(buyLeg);
        }
        return sendableLegs;
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
    private RebalanceGroupValidation validateGroup(String associationId, List<TornStockNoticeAuditDO> group) {
        if (CollectionUtils.isEmpty(group)) {
            return RebalanceGroupValidation.invalid("关联组不存在任何通知");
        }
        if (group.size() != REBALANCE_LEG_COUNT) {
            return RebalanceGroupValidation.invalid("关联组通知数不为2: " + group.size());
        }
        RebalanceGroupValidation legs = resolveLegs(group);
        if (!legs.passed()) {
            return legs;
        }
        String conflictReason = resolveAssociationConflictReason(associationId, legs.sellLeg(), legs.buyLeg());
        if (conflictReason != null) {
            return RebalanceGroupValidation.invalid(conflictReason);
        }
        return RebalanceGroupValidation.valid(legs.sellLeg(), legs.buyLeg());
    }

    /**
     * 在关联组完整通知集合中定位唯一的SELL腿与BUY腿。
     *
     * @param group 关联组完整通知集合
     * @return 定位结果;存在非法腿、重复腿、缺腿或缺少主键时返回失败原因
     */
    private RebalanceGroupValidation resolveLegs(List<TornStockNoticeAuditDO> group) {
        List<TornStockNoticeAuditDO> sellLegs = new ArrayList<>(1);
        List<TornStockNoticeAuditDO> buyLegs = new ArrayList<>(1);
        for (TornStockNoticeAuditDO leg : group) {
            StockAlphaRebalanceLegEnum legKind = matchLegKind(leg);
            if (legKind == null) {
                return RebalanceGroupValidation.invalid(describeIllegalLeg(leg));
            }
            if (legKind == StockAlphaRebalanceLegEnum.SELL) {
                sellLegs.add(leg);
            } else {
                buyLegs.add(leg);
            }
        }
        return resolveSingleLegs(sellLegs, buyLegs);
    }

    /**
     * 校验同一类腿只出现一次且两腿齐备、主键有效。
     *
     * @param sellLegs 被识别为SELL腿的通知
     * @param buyLegs  被识别为BUY腿的通知
     * @return 校验结果
     */
    private RebalanceGroupValidation resolveSingleLegs(List<TornStockNoticeAuditDO> sellLegs,
                                                       List<TornStockNoticeAuditDO> buyLegs) {
        if (sellLegs.size() > 1) {
            return RebalanceGroupValidation.invalid("关联组出现重复SELL腿");
        }
        if (buyLegs.size() > 1) {
            return RebalanceGroupValidation.invalid("关联组出现重复BUY腿");
        }
        if (sellLegs.isEmpty()) {
            return RebalanceGroupValidation.invalid("关联组缺少SELL腿");
        }
        if (buyLegs.isEmpty()) {
            return RebalanceGroupValidation.invalid("关联组缺少BUY腿");
        }
        TornStockNoticeAuditDO sellLeg = sellLegs.getFirst();
        TornStockNoticeAuditDO buyLeg = buyLegs.getFirst();
        if (sellLeg.getId() == null || buyLeg.getId() == null) {
            return RebalanceGroupValidation.invalid("关联组通知缺少主键");
        }
        return RebalanceGroupValidation.valid(sellLeg, buyLeg);
    }

    /**
     * 解析通知对应的换仓腿标识与腿顺序。
     *
     * @param leg 关联组中的一条通知
     * @return SELL或BUY;通知类型非法、腿标识或腿顺序不符时返回null
     */
    private StockAlphaRebalanceLegEnum matchLegKind(TornStockNoticeAuditDO leg) {
        if (!isRebalanceNotice(leg)) {
            return null;
        }
        String legCode = StockNoticePayloadReader.readText(leg, FIELD_REBALANCE_LEG);
        Integer legOrder = StockNoticePayloadReader.readInt(leg, FIELD_LEG_ORDER);
        if (matchesLeg(StockAlphaRebalanceLegEnum.SELL, legCode, legOrder)) {
            return StockAlphaRebalanceLegEnum.SELL;
        }
        if (matchesLeg(StockAlphaRebalanceLegEnum.BUY, legCode, legOrder)) {
            return StockAlphaRebalanceLegEnum.BUY;
        }
        return null;
    }

    /**
     * 判断腿标识与腿顺序是否与枚举定义一致。
     *
     * @param legKind  腿枚举
     * @param legCode  payload固化的腿标识
     * @param legOrder payload固化的腿顺序
     * @return 一致返回true
     */
    private boolean matchesLeg(StockAlphaRebalanceLegEnum legKind, String legCode, Integer legOrder) {
        return legKind.getCode().equals(legCode) && Integer.valueOf(legKind.getLegOrder()).equals(legOrder);
    }

    /**
     * 描述非法腿的具体原因,区分通知类型非法与腿标识/腿顺序非法。
     *
     * @param leg 关联组中的一条通知
     * @return 失败原因
     */
    private String describeIllegalLeg(TornStockNoticeAuditDO leg) {
        if (!isRebalanceNotice(leg)) {
            return "关联组通知类型非法: " + noticeTypeOf(leg);
        }
        return "关联组腿标识或腿顺序非法: leg=" + StockNoticePayloadReader.readText(leg, FIELD_REBALANCE_LEG)
                + ", legOrder=" + StockNoticePayloadReader.readInt(leg, FIELD_LEG_ORDER);
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
        if (!associationId.equals(StockNoticePayloadReader.readText(sellLeg, FIELD_REBALANCE_ASSOCIATION_ID))
                || !associationId.equals(StockNoticePayloadReader.readText(buyLeg, FIELD_REBALANCE_ASSOCIATION_ID))) {
            return "关联组换仓关联标识不一致";
        }
        for (String field : REBALANCE_ASSOCIATION_FIELDS) {
            String sellValue = StockNoticePayloadReader.readText(sellLeg, field);
            String buyValue = StockNoticePayloadReader.readText(buyLeg, field);
            if (sellValue == null) {
                return "关联组换仓关联字段缺失: " + field;
            }
            if (!sellValue.equals(buyValue)) {
                return "关联组换仓关联字段冲突: " + field;
            }
        }
        if (!String.valueOf(sellLeg.getBatchId()).equals(
                StockNoticePayloadReader.readText(sellLeg, FIELD_ORIGINAL_BATCH_ID))
                || !String.valueOf(buyLeg.getBatchId()).equals(
                StockNoticePayloadReader.readText(buyLeg, FIELD_REPLACEMENT_BATCH_ID))) {
            return "关联组腿与关联批次绑定不一致";
        }
        return null;
    }

    /**
     * 解析本次发送的最终换仓消息文本。
     * <p>
     * 两腿均未冻结时按当前两腿重新组合;两腿均已冻结(含自动重发)时复用同一冻结文本,
     * 文本不一致即判定无法恢复;一腿已冻结、一腿未冻结时,已冻结文本必须与当前两腿可组合文本
     * 完全一致才可用于补齐另一腿,否则fail-closed,禁止把单腿文本当成完整换仓消息。
     *
     * @param sellLeg  原仓卖出腿
     * @param buyLeg   新仓买入腿
     * @param batchMap 批次ID到批次DO的映射
     * @return 最终消息上下文;无法恢复完整一致消息时返回null
     */
    private RebalanceMessage resolveMessage(TornStockNoticeAuditDO sellLeg, TornStockNoticeAuditDO buyLeg,
                                            Map<Long, TornStockVirtualBatchDO> batchMap) {
        boolean sellFrozen = StockNoticePayloadReader.isAlreadyFrozen(sellLeg);
        boolean buyFrozen = StockNoticePayloadReader.isAlreadyFrozen(buyLeg);
        if (sellFrozen && buyFrozen) {
            return resolveFrozenPairMessage(sellLeg, buyLeg);
        }
        String composedText = composeGroupText(sellLeg, buyLeg, batchMap);
        if (composedText == null) {
            return null;
        }
        if (sellFrozen || buyFrozen) {
            return resolvePartiallyFrozenMessage(sellFrozen ? sellLeg : buyLeg, composedText);
        }
        return new RebalanceMessage(composedText, null);
    }

    /**
     * 复用两腿均已冻结的同一最终文本,文本不一致即判定无法恢复完整换仓消息。
     *
     * @param sellLeg 原仓卖出腿
     * @param buyLeg  新仓买入腿
     * @return 最终消息上下文;两腿冻结文本不一致时返回null
     */
    private RebalanceMessage resolveFrozenPairMessage(TornStockNoticeAuditDO sellLeg,
                                                      TornStockNoticeAuditDO buyLeg) {
        String sellText = StockNoticePayloadReader.readFrozenMessageText(sellLeg.getPayloadSnapshot());
        String buyText = StockNoticePayloadReader.readFrozenMessageText(buyLeg.getPayloadSnapshot());
        if (sellText == null || !sellText.equals(buyText)) {
            log.error("股票通知发送-α换仓两腿已冻结文本不一致,无法恢复完整换仓消息: sellNoticeId={}, buyNoticeId={}",
                    sellLeg.getId(), buyLeg.getId());
            return null;
        }
        return new RebalanceMessage(sellText, null);
    }

    /**
     * 一腿已冻结时校验已冻结文本与当前两腿可组合文本完全一致,并沿用已冻结腿的冻结时间。
     *
     * @param frozenLeg    已冻结的腿
     * @param composedText 当前两腿可组合文本
     * @return 最终消息上下文;已冻结文本缺失、时间缺失或与可组合文本不一致时返回null
     */
    private RebalanceMessage resolvePartiallyFrozenMessage(TornStockNoticeAuditDO frozenLeg, String composedText) {
        String frozenText = StockNoticePayloadReader.readFrozenMessageText(frozenLeg.getPayloadSnapshot());
        LocalDateTime frozenAt = StockNoticePayloadReader.readFrozenAt(frozenLeg.getPayloadSnapshot());
        if (frozenText == null || frozenAt == null || !frozenText.equals(composedText)) {
            log.error("股票通知发送-α换仓一腿已冻结但无法恢复完整一致换仓消息,禁止发送: associationId={}, "
                            + "frozenNoticeId={}",
                    StockNoticePayloadReader.readText(frozenLeg, FIELD_REBALANCE_ASSOCIATION_ID),
                    frozenLeg.getId());
            return null;
        }
        return new RebalanceMessage(frozenText, frozenAt);
    }

    /**
     * 将关联组两腿组合为一条α换仓消息,并要求组合结果完整包含两腿。
     *
     * @param sellLeg  原仓卖出腿
     * @param buyLeg   新仓买入腿
     * @param batchMap 批次ID到批次DO的映射
     * @return 组合后的换仓消息文本;无法组合出完整两腿消息时返回null
     */
    private String composeGroupText(TornStockNoticeAuditDO sellLeg, TornStockNoticeAuditDO buyLeg,
                                    Map<Long, TornStockVirtualBatchDO> batchMap) {
        if (CollectionUtils.isEmpty(batchMap)) {
            return null;
        }
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
     * 两腿均已冻结(自动重发)时不做任何冻结更新,直接复用已冻结payload,不得覆盖
     * messageText/frozenAt/payloadHash;部分冻结时只补齐未冻结腿,并沿用已冻结腿的冻结时间
     * 保证两腿处于同一最终消息上下文。冻结只能作用于本次领取者持有的SENDING通知。
     *
     * @param sellLeg    原仓卖出腿
     * @param buyLeg     新仓买入腿
     * @param message    已解析的最终消息上下文
     * @param claimToken 本次领取标识
     * @return 冻结成功返回true;行数不符返回false
     */
    private boolean freezeUnfrozenLegs(TornStockNoticeAuditDO sellLeg, TornStockNoticeAuditDO buyLeg,
                                       RebalanceMessage message, String claimToken) {
        List<Long> unfrozenLegIds = new ArrayList<>(REBALANCE_LEG_COUNT);
        if (!StockNoticePayloadReader.isAlreadyFrozen(sellLeg)) {
            unfrozenLegIds.add(sellLeg.getId());
        }
        if (!StockNoticePayloadReader.isAlreadyFrozen(buyLeg)) {
            unfrozenLegIds.add(buyLeg.getId());
        }
        if (unfrozenLegIds.isEmpty()) {
            return true;
        }
        LocalDateTime frozenAt = message.frozenAt() != null ? message.frozenAt() : LocalDateTime.now();
        Map<Long, TornStockNoticeAuditDO> legById = HashMap.newHashMap(REBALANCE_LEG_COUNT);
        legById.put(sellLeg.getId(), sellLeg);
        legById.put(buyLeg.getId(), buyLeg);
        return sendRecorder.freezePayload(legById, unfrozenLegIds, message.messageText(), frozenAt, claimToken);
    }

    /**
     * 记录α换仓关联组异常并将剩余可发送腿置为人工核验终态(FAILED_FINAL)。
     * <p>
     * 关联组缺腿、重复腿、字段冲突、格式非法或状态不一致时禁止调用Bot;置为FAILED_FINAL是本批次
     * 明确的人工处理规则,既不静默重复发送也不静默丢弃,并保留统一关联事实供人工核验。
     * 达到总尝试上限的失败腿由回写直接进入FAILED_FINAL,不经过本方法。
     *
     * @param pendingLegs 本轮仍可发送的关联组腿
     * @param reason      异常原因
     * @param counter     发送计数
     */
    private void markGroupAnomaly(List<TornStockNoticeAuditDO> pendingLegs, String reason,
                                  NoticeSendCounter counter) {
        List<Long> noticeIds = pendingLegs == null ? List.of() : pendingLegs.stream()
                .map(TornStockNoticeAuditDO::getId)
                .filter(Objects::nonNull)
                .toList();
        counter.countFailure();
        log.error("股票通知发送-α换仓关联组异常,禁止调用Bot: reason={}, noticeIds={}", reason, noticeIds);
        if (noticeIds.isEmpty()) {
            return;
        }
        sendRecorder.markFinal(noticeIds, REBALANCE_GROUP_ANOMALY_PREFIX + reason);
    }

    /**
     * 构建关联组状态不一致的失败原因。
     *
     * @param sellLeg 原仓卖出腿
     * @param buyLeg  新仓买入腿
     * @return 状态不一致失败原因
     */
    private String partialCompletionReason(TornStockNoticeAuditDO sellLeg, TornStockNoticeAuditDO buyLeg) {
        return "关联组状态不一致,仅一腿可发送,禁止重复发送或静默丢弃: sellStatus="
                + sendStatusOf(sellLeg) + ", buyStatus=" + sendStatusOf(buyLeg);
    }

    /**
     * 判断通知是否处于可领取发送状态且未达总尝试上限。
     *
     * @param notice 通知审计
     * @return 可发送返回true
     */
    private boolean isSendableNotice(TornStockNoticeAuditDO notice) {
        if (notice == null || !StockNoticeStatusEnum.isClaimable(notice.getSendStatus())) {
            return false;
        }
        Integer attemptCount = notice.getSendAttemptCount();
        return attemptCount == null || attemptCount < StockNoticeSendRecorder.MAX_SEND_ATTEMPTS;
    }

    /**
     * 读取通知类型文本,通知为空时返回null。
     *
     * @param notice 通知审计
     * @return 通知类型
     */
    private String noticeTypeOf(TornStockNoticeAuditDO notice) {
        return notice == null ? null : notice.getNoticeType();
    }

    /**
     * 读取通知的发送状态,通知为空时返回null。
     *
     * @param notice 通知审计
     * @return 发送状态
     */
    private String sendStatusOf(TornStockNoticeAuditDO notice) {
        return notice == null ? null : notice.getSendStatus();
    }

    /**
     * α换仓关联组校验结果。
     *
     * @param passed  是否通过校验
     * @param reason  校验失败原因;通过时为null
     * @param sellLeg 原仓卖出腿;校验失败时为null
     * @param buyLeg  新仓买入腿;校验失败时为null
     */
    private record RebalanceGroupValidation(
            boolean passed,
            String reason,
            TornStockNoticeAuditDO sellLeg,
            TornStockNoticeAuditDO buyLeg) {
        private static RebalanceGroupValidation valid(TornStockNoticeAuditDO sellLeg,
                                                      TornStockNoticeAuditDO buyLeg) {
            return new RebalanceGroupValidation(true, null, sellLeg, buyLeg);
        }

        private static RebalanceGroupValidation invalid(String reason) {
            return new RebalanceGroupValidation(false, reason, null, null);
        }
    }

    /**
     * α换仓关联组最终消息上下文。
     *
     * @param messageText 两腿共用的最终消息文本
     * @param frozenAt    已存在的冻结时间;仅部分冻结补齐时非null
     */
    private record RebalanceMessage(
            String messageText,
            LocalDateTime frozenAt) {
    }

    /**
     * α换仓通知按关联标识分组后的分流结果。
     *
     * @param groups           换仓关联标识到该关联组可发送腿的映射
     * @param malformedNotices 通知类型为α换仓但缺少换仓关联标识的通知
     */
    private record RebalancePartition(
            Map<String, List<TornStockNoticeAuditDO>> groups,
            List<TornStockNoticeAuditDO> malformedNotices) {
    }
}
