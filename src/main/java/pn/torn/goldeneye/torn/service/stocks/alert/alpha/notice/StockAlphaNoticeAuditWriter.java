package pn.torn.goldeneye.torn.service.stocks.alert.alpha.notice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.NoticeRebalanceAssociation;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeAuditWriter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * α换仓通知审计写入器 - α轨道的审计写入唯一宿主。
 * <p>
 * 本类隔离α执行链与通用通知审计实现:{@code StockAlphaRebalanceService} 只依赖本类,
 * 不直接接触通知审计的构造细节。按轨道分流:
 * <ul>
 *   <li>正式α轨道:保持搬迁前的两条腿PENDING审计,通知模板、内容与发送时刻零改动;</li>
 *   <li>影子α轨道:一次换仓的双腿合并为一条{@link pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeStatusEnum#SHADOW_RECORDED}
 *       终态记录,只记录不投递。</li>
 * </ul>
 *
 * @author Bai
 * @version 1.6.5
 * @since 2026.09.18
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAlphaNoticeAuditWriter {

    private final StockNoticeAuditWriter noticeAuditWriter;

    /**
     * 写入正式α轨道换仓审计。
     * <p>
     * 调用通用写入器的双腿语义:原仓写SELL腿、新仓写BUY腿,两条记录共享同一换仓关联标识,
     * 状态为PENDING并进入既有发送链,与搬迁前的正式α行为完全一致。
     *
     * @param replacement       换仓后的新仓批次
     * @param original          换仓前的原仓批次
     * @param executionBarStart 执行桶起点
     * @param association       α换仓统一关联事实
     */
    public void writeFormalRebalanceAudits(TornStockVirtualBatchDO replacement, TornStockVirtualBatchDO original,
                                           LocalDateTime executionBarStart,
                                           NoticeRebalanceAssociation association) {
        noticeAuditWriter.writeNoticeAudits(List.of(replacement), List.of(original), executionBarStart, association);
    }

    /**
     * 写入影子α轨道换仓审计(只记录不投递)。
     *
     * @param replacement       换仓后的新仓批次
     * @param original          换仓前的原仓批次
     * @param executionBarStart 执行桶起点
     * @param association       α换仓统一关联事实
     */
    public void writeShadowRebalanceAudit(TornStockVirtualBatchDO replacement, TornStockVirtualBatchDO original,
                                          LocalDateTime executionBarStart,
                                          NoticeRebalanceAssociation association) {
        noticeAuditWriter.writeShadowRebalanceAudit(replacement, original, executionBarStart, association);
    }
}
