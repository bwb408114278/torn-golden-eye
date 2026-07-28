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
import pn.torn.goldeneye.napcat.send.msg.GroupMsgHttpBuilder;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.utils.JsonUtils;

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
     * Bot发送失败时的统一失败原因。
     */
    private static final String BOT_SEND_FAILURE_MESSAGE = "Bot返回null响应";
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
        List<TornStockNoticeAuditDO> validNotices = filterNoticesWithBatches(pendingNotices, batchMap);
        if (validNotices.size() < pendingNotices.size()) {
            markMissingBatchNoticesFailed(pendingNotices, batchMap);
        }
        if (validNotices.isEmpty()) {
            log.warn("股票通知发送-无有效批次关联, pendingNotices={}", pendingNotices.size());
            return;
        }

        List<StockNoticeComposeService.ComposedMessage> composedMessages =
                stockNoticeComposeService.composeAndMergeNotices(validNotices, batchMap);
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
                markNoticesFailed(composedMessage.noticeIds());
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
     *   <li>NapCat业务结果retcode == 0(解析body JSON中的retcode字段)</li>
     * </ol>
     * 发送过程中抛出的任何异常都会被捕获并记录日志,方法返回false,不向上抛出。
     *
     * @param text 待发送的中文消息文本
     * @return true表示发送成功(2xx且body非空且retcode=0);false表示发送失败、响应异常或无法确认成功
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
            String body = response.getBody();
            if (body == null) {
                log.warn("股票通知发送-响应body为空, 无法确认发送成功");
                return false;
            }
            if (!isNapCatSuccess(body)) {
                log.warn("股票通知发送-NapCat业务结果非成功, body={}", body);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("股票通知发送-单条消息发送异常", e);
            return false;
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
        List<TornStockVirtualBatchDO> batches = virtualBatchDAO.listByIds(batchIds);
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
            noticeAuditDAO.markFailedByIds(missingNoticeIds, "关联虚拟交易批次不存在");
            log.warn("股票通知发送-无关联批次通知已标记FAILED: count={}", missingNoticeIds.size());
        }
    }

    /**
     * 将一批通知标记为已发送(SENT)并设置发送成功时间。
     * <p>
     * 单条更新异常不影响其他通知,异常被捕获并记录日志。
     *
     * @param noticeIds 本批次组合消息对应的通知ID列表
     */
    private void markNoticesSent(List<Long> noticeIds) {
        if (noticeIds == null || noticeIds.isEmpty()) {
            return;
        }
        try {
            noticeAuditDAO.markSentByIds(noticeIds);
        } catch (Exception e) {
            log.error("股票通知发送-批量标记SENT状态异常, noticeCount={}", noticeIds.size(), e);
        }
    }

    /**
     * 将一批通知标记为发送失败(FAILED)并记录错误信息
     * <p>
     * 超长错误信息会被截断到 {@link #MAX_ERROR_MESSAGE_LENGTH} 以避免库字段溢出。
     * 单条更新异常不影响其他通知,异常被捕获并记录日志。
     *
     * @param noticeIds 本批次组合消息对应的通知ID列表
     */
    private void markNoticesFailed(List<Long> noticeIds) {
        if (noticeIds == null || noticeIds.isEmpty()) {
            return;
        }
        try {
            noticeAuditDAO.markSendFailedByIds(noticeIds, StockNoticeSendService.BOT_SEND_FAILURE_MESSAGE);
        } catch (Exception e) {
            log.error("股票通知发送-批量标记FAILED状态异常, noticeCount={}", noticeIds.size(), e);
        }
    }
}
