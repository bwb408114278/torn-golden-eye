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
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeStatusEnum;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgHttpBuilder;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;

import java.time.LocalDateTime;
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
 *   <li>调用 {@link StockNoticeComposeService#composeAndMergeNotices} 组合消息</li>
 *   <li>逐条构建 {@link GroupMsgHttpBuilder} + {@link TextQqMsg} 发送,HTTP 2xx且body非空时更新SENT,
 *       异常、非2xx或body为null更新FAILED</li>
 *   <li>本期不自动重试,sendAttemptCount从0改为1</li>
 * </ol>
 * 单条通知发送异常不会中断后续通知投递,异常信息写入errorMessage字段。
 *
 * @author Bai
 * @version 1.2.12
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
     * 消息通知规则版本
     */
    public static final String MESSAGE_RULE_VERSION = "1.0.0";
    /**
     * 初始发送尝试次数(PENDING通知首次发送,attemptCount从0置为1)
     */
    private static final int INITIAL_SEND_ATTEMPT_COUNT = 1;
    /**
     * 错误信息最大长度(截断超长异常信息避免库字段溢出)
     */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private final Bot bot;
    private final ProjectProperty projectProperty;
    private final SysSettingManager sysSettingManager;
    private final TornStockNoticeAuditDAO noticeAuditDAO;
    private final TornStockVirtualBatchDAO virtualBatchDAO;
    private final StockNoticeComposeService stockNoticeComposeService;

    /**
     * 发送全部待发送(PENDING)通知
     * <p>
     * 事务提交后调用。执行流程:
     * <ol>
     *   <li>校验 {@link SettingConstants#KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED} 开关,非"true"直接返回</li>
     *   <li>查询全部PENDING通知,无记录直接返回</li>
     *   <li>批量查询关联批次信息(用通知的batchId集合)</li>
     *   <li>调用 {@link StockNoticeComposeService#composeAndMergeNotices} 组合并拆分消息</li>
     *   <li>逐条发送: HTTP 2xx且body非空时更新SENT并设置sentAt;异常、非2xx或body为null更新FAILED并记录errorMessage</li>
     * </ol>
     * 单条通知发送异常不中断后续投递,整个方法不抛出异常(内部捕获并记录)。
     */
    public void sendPendingNotices() {
        String enabled = sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED);
        if (!SETTING_ENABLED_VALUE.equalsIgnoreCase(enabled)) {
            log.debug("股票通知发送-正式买卖消息开关关闭,跳过发送");
            return;
        }

        List<TornStockNoticeAuditDO> pendingNotices = noticeAuditDAO.selectPendingNotices();
        if (CollectionUtils.isEmpty(pendingNotices)) {
            log.debug("股票通知发送-无待发送通知");
            return;
        }

        log.info("股票通知发送-发现{}条待发送通知", pendingNotices.size());

        Map<Long, TornStockVirtualBatchDO> batchMap = loadBatchMap(pendingNotices);

        List<StockNoticeComposeService.ComposedMessage> composedMessages =
                stockNoticeComposeService.composeAndMergeNotices(pendingNotices, batchMap);
        if (CollectionUtils.isEmpty(composedMessages)) {
            log.warn("股票通知发送-消息组合结果为空,待发送通知数={}", pendingNotices.size());
            return;
        }

        int successCount = 0;
        int failedCount = 0;
        for (StockNoticeComposeService.ComposedMessage composedMessage : composedMessages) {
            boolean sent = sendSingleMessage(composedMessage.text());
            if (sent) {
                successCount++;
                markNoticesSent(composedMessage.noticeIds());
            } else {
                failedCount++;
                markNoticesFailed(composedMessage.noticeIds(), "Bot返回null响应");
            }
        }

        log.info("股票通知发送-完成, 成功={}条, 失败={}条", successCount, failedCount);
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
     * </ol>
     * 发送过程中抛出的任何异常都会被捕获并记录日志,方法返回false,不向上抛出。
     *
     * @param text 待发送的中文消息文本
     * @return true表示发送成功(2xx且body非空);false表示发送失败、响应异常或无法确认成功
     */
    public boolean sendSingleMessage(String text) {
        try {
            BotHttpReqParam param = new GroupMsgHttpBuilder()
                    .setGroupId(projectProperty.getVipGroupId())
                    .addMsg(new TextQqMsg(text))
                    .build();
            ResponseEntity<String> response = bot.sendRequest(param, String.class);
            if (response == null) {
                log.warn("股票通知发送-Bot返回null响应, 发送失败");
                return false;
            }
            if (!response.getStatusCode().is2xxSuccessful()) {
                log.warn("股票通知发送-HTTP状态非2xx, 发送失败, statusCode={}", response.getStatusCode());
                return false;
            }
            if (response.getBody() == null) {
                log.warn("股票通知发送-响应body为空, 无法确认发送成功");
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("股票通知发送-单条消息发送异常", e);
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
        List<TornStockVirtualBatchDO> batches = virtualBatchDAO.listByIds(batchIds);
        if (CollectionUtils.isEmpty(batches)) {
            return Collections.emptyMap();
        }
        return batches.stream()
                .collect(Collectors.toMap(TornStockVirtualBatchDO::getId, Function.identity()));
    }

    /**
     * 将一批通知标记为已发送(SENT)并设置发送成功时间
     * <p>
     * 单条更新异常不影响其他通知,异常被捕获并记录日志。
     *
     * @param noticeIds 本批次组合消息对应的通知ID列表
     */
    private void markNoticesSent(List<Long> noticeIds) {
        LocalDateTime now = LocalDateTime.now();
        for (Long noticeId : noticeIds) {
            try {
                TornStockNoticeAuditDO notice = new TornStockNoticeAuditDO();
                notice.setId(noticeId);
                notice.setSendStatus(StockNoticeStatusEnum.SENT.getCode());
                notice.setSentAt(now);
                notice.setAttemptedAt(now);
                notice.setSendAttemptCount(INITIAL_SEND_ATTEMPT_COUNT);
                noticeAuditDAO.updateById(notice);
            } catch (Exception e) {
                log.error("股票通知发送-标记SENT状态异常, noticeId={}", noticeId, e);
            }
        }
    }

    /**
     * 将一批通知标记为发送失败(FAILED)并记录错误信息
     * <p>
     * 超长错误信息会被截断到 {@link #MAX_ERROR_MESSAGE_LENGTH} 以避免库字段溢出。
     * 单条更新异常不影响其他通知,异常被捕获并记录日志。
     *
     * @param noticeIds    本批次组合消息对应的通知ID列表
     * @param errorMessage 失败错误信息
     */
    private void markNoticesFailed(List<Long> noticeIds, String errorMessage) {
        String truncatedError = truncateErrorMessage(errorMessage);
        LocalDateTime now = LocalDateTime.now();
        for (Long noticeId : noticeIds) {
            try {
                TornStockNoticeAuditDO notice = new TornStockNoticeAuditDO();
                notice.setId(noticeId);
                notice.setSendStatus(StockNoticeStatusEnum.FAILED.getCode());
                notice.setAttemptedAt(now);
                notice.setSendAttemptCount(INITIAL_SEND_ATTEMPT_COUNT);
                notice.setErrorMessage(truncatedError);
                noticeAuditDAO.updateById(notice);
            } catch (Exception e) {
                log.error("股票通知发送-标记FAILED状态异常, noticeId={}", noticeId, e);
            }
        }
    }

    /**
     * 截断错误信息到最大长度
     *
     * @param errorMessage 原始错误信息
     * @return 截断后的错误信息;入参为null时返回null
     */
    private String truncateErrorMessage(String errorMessage) {
        if (errorMessage == null) {
            return null;
        }
        if (errorMessage.length() <= MAX_ERROR_MESSAGE_LENGTH) {
            return errorMessage;
        }
        return errorMessage.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }
}
