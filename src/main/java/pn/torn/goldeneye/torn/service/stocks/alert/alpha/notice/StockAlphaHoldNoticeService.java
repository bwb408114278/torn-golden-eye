package pn.torn.goldeneye.torn.service.stocks.alert.alpha.notice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeTypeEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.track.StockAlphaPhaseTrack;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.track.StockAlphaTrackRegistry;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockRuleVersion;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticePayloadCanonicalizer;
import pn.torn.goldeneye.utils.JsonUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * α继续持有通知服务 - α决策日目标未变化时通知审计写入的唯一宿主。
 * <p>
 * 触发语义:α决策日该轨道未产生{@code ALPHA_REBALANCE}(持仓仍在Top3内、目标未变化)时,
 * 由轮次编排在执行桶内调用本服务落一条继续持有通知,使成员能区分"本周无动作"与"系统异常"。
 * 本条不是换仓腿,不参与{@code rebalanceAssociationId}组,不占用两腿成组校验。
 * <p>
 * 分流与投递:
 * <ul>
 *   <li>正式α轨道:写{@link StockNoticeStatusEnum#PENDING}审计行,正文自包含(标题与正文在创建时固化),
 *       由既有{@code StockNoticeSendService#sendPendingNotices}在同一轮次事务提交后领取、冻结并投递;</li>
 *   <li>α影子轨道:写{@link StockNoticeStatusEnum#SHADOW_RECORDED}终态记录,只留痕不投递。</li>
 * </ul>
 * <p>
 * 幂等:业务唯一键为{@code (决策业务日, 轨道编码)},由数据库部分唯一索引
 * {@code uk_stock_notice_audit_alpha_hold}兜底;重放或轮次重试时先回读既有行直接复用,
 * 不重复建行、不重复发送,也不覆盖首次固化的正文。
 * <p>
 * 通知在轮次事务内写入、事务提交后才发送,与既有买卖通知与换仓通知同一边界。
 *
 * @author Bai
 * @version 1.6.6
 * @since 2026.10.01
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAlphaHoldNoticeService {

    /**
     * 通知编号前缀(与批次N、日报D、年报A区分)。
     */
    private static final String NOTICE_NO_PREFIX = "H";
    /**
     * 通知编号时间戳格式。
     */
    private static final String NOTICE_NO_TIMESTAMP_PATTERN = "yyyyMMddHHmmssSSS";
    /**
     * 通知编号格式化器。
     */
    private static final DateTimeFormatter NOTICE_NO_FORMATTER =
            DateTimeFormatter.ofPattern(NOTICE_NO_TIMESTAMP_PATTERN);

    private final TornStockNoticeAuditDAO noticeAuditDAO;
    private final ProjectProperty projectProperty;
    private final StockMarketClock marketClock;

    /**
     * 写入α继续持有通知审计。
     * <p>
     * 同一决策业务日、同一轨道的通知已存在时只记录日志并复用既有行:轮次重试、进程重启或决策重放
     * 都不会产生第二条继续持有通知,也不会覆盖首次冻结的正文与哈希。
     *
     * @param track                 目标相位轨道
     * @param decisionBusinessDate  决策业务日(排名窗口最后共同有效日)
     * @param phase                 本次消费的相位编号
     * @param executionBarStartTime 持久化执行桶起点(通知计划发送时刻)
     * @param currentBatch          该轨道当前开放持仓批次
     */
    public void recordHoldNotice(StockAlphaPhaseTrack track, LocalDate decisionBusinessDate, Integer phase,
                                 LocalDateTime executionBarStartTime, TornStockVirtualBatchDO currentBatch) {
        Objects.requireNonNull(track, "相位轨道不能为空");
        Objects.requireNonNull(decisionBusinessDate, "决策业务日不能为空");
        Objects.requireNonNull(currentBatch, "当前持仓批次不能为空");
        TornStockNoticeAuditDO existing = noticeAuditDAO.selectBySummaryDateTypeAndTrack(
                decisionBusinessDate, StockNoticeTypeEnum.ALPHA_HOLD.getCode(), track.trackCode());
        if (existing != null) {
            log.info("α继续持有通知-同决策日同轨道审计行已存在,复用且不重复发送: trackCode={}, decisionBusinessDate={}, noticeNo={}",
                    track.trackCode(), decisionBusinessDate, existing.getNoticeNo());
            return;
        }
        boolean production = StockAlphaTrackRegistry.productionTrack().equals(track);
        String messageText = StockAlphaNoticeRenderer.renderHold(currentBatch.getStocksShortname(),
                decisionBusinessDate);
        TornStockNoticeAuditDO notice = new TornStockNoticeAuditDO();
        notice.setNoticeNo(generateNoticeNo(track));
        notice.setBatchId(currentBatch.getId());
        notice.setNoticeType(StockNoticeTypeEnum.ALPHA_HOLD.getCode());
        notice.setSummaryDate(decisionBusinessDate);
        notice.setTrackCode(track.trackCode());
        notice.setGroupId(projectProperty.getVipGroupId());
        notice.setScheduledRoundTime(executionBarStartTime);
        notice.setSendStatus(production
                ? StockNoticeStatusEnum.PENDING.getCode()
                : StockNoticeStatusEnum.SHADOW_RECORDED.getCode());
        notice.setSendAttemptCount(0);
        notice.setMessageRuleVersion(StockRuleVersion.MESSAGE);
        notice.setPayloadSnapshot(buildPayloadSnapshot(track, decisionBusinessDate, phase, executionBarStartTime,
                currentBatch, messageText));
        notice.setPayloadHash(StockNoticePayloadCanonicalizer.sha256(notice.getPayloadSnapshot()));
        noticeAuditDAO.save(notice);
        log.info("α继续持有通知审计写入完成: trackCode={}, decisionBusinessDate={}, phase={}, sendStatus={}, noticeNo={}",
                track.trackCode(), decisionBusinessDate, phase, notice.getSendStatus(), notice.getNoticeNo());
    }

    /**
     * 生成通知编号。
     * <p>
     * 格式: "H" + yyyyMMddHHmmssSSS + "-" + 轨道编码。时间戳保证跨日跨轨道行互不相同,
     * 轨道后缀保证同一毫秒内多轨道各自成行,不与批次N、日报D、年报A编号冲突。
     *
     * @param track 目标相位轨道
     * @return 通知编号
     */
    private String generateNoticeNo(StockAlphaPhaseTrack track) {
        return NOTICE_NO_PREFIX + marketClock.now().format(NOTICE_NO_FORMATTER) + "-" + track.trackCode();
    }

    /**
     * 构造继续持有通知载荷快照。
     * <p>
     * 载荷固化决策业务日、轨道、相位、执行桶、当前持仓批次与最终投递正文,使审计行无需回查批次
     * 即可复核该通知的触发事实;正文自包含,发送链只读取{@code messageText}与{@code frozenAt}。
     *
     * @param track                 目标相位轨道
     * @param decisionBusinessDate  决策业务日
     * @param phase                 本次消费的相位编号
     * @param executionBarStartTime 持久化执行桶起点
     * @param currentBatch          当前开放持仓批次
     * @param messageText           最终投递正文
     * @return 规范化载荷快照JSON文本
     */
    private String buildPayloadSnapshot(StockAlphaPhaseTrack track, LocalDate decisionBusinessDate, Integer phase,
                                        LocalDateTime executionBarStartTime, TornStockVirtualBatchDO currentBatch,
                                        String messageText) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("noticeType", StockNoticeTypeEnum.ALPHA_HOLD.getCode());
        payload.put("trackCode", track.trackCode());
        payload.put("portfolioCode", track.portfolioCode());
        payload.put("slotNo", track.slotNo());
        payload.put("decisionBusinessDate", decisionBusinessDate.toString());
        payload.put("phase", phase);
        payload.put("executionBarStartTime",
                executionBarStartTime == null ? null : executionBarStartTime.toString());
        payload.put("batchId", currentBatch.getId());
        payload.put("batchNo", currentBatch.getBatchNo());
        payload.put("stocksId", currentBatch.getStocksId());
        payload.put("stocksShortname", currentBatch.getStocksShortname());
        payload.put("groupId", projectProperty.getVipGroupId());
        payload.put("messageRuleVersion", StockRuleVersion.MESSAGE);
        payload.put("messageText", messageText);
        return StockNoticePayloadCanonicalizer.canonicalize(JsonUtils.objToJson(payload));
    }
}
