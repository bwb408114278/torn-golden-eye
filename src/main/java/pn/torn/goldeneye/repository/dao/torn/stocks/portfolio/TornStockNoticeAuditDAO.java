package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockNoticeAuditMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn股票通知审计持久层类
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Repository
public class TornStockNoticeAuditDAO extends ServiceImpl<TornStockNoticeAuditMapper, TornStockNoticeAuditDO> {

    /**
     * 查询待发送通知,批量获取避免N+1
     *
     * @return 待发送通知列表
     */
    public List<TornStockNoticeAuditDO> selectPendingNotices() {
        return baseMapper.selectPendingNotices();
    }

    /**
     * 批量标记无关联批次的通知为FAILED。
     *
     * @param noticeIds    通知ID列表
     * @param errorMessage 错误信息
     * @return 更新行数
     */
    public int markFailedByIds(List<Long> noticeIds, String errorMessage) {
        if (noticeIds == null || noticeIds.isEmpty()) {
            return 0;
        }
        return baseMapper.markFailedByIds(noticeIds, errorMessage);
    }

    /**
     * 批量标记通知发送成功。
     *
     * @param noticeIds 通知ID列表
     * @return 更新行数
     */
    public int markSentByIds(List<Long> noticeIds) {
        if (noticeIds == null || noticeIds.isEmpty()) {
            return 0;
        }
        return baseMapper.markSentByIds(noticeIds);
    }

    /**
     * 批量标记通知发送失败。
     *
     * @param noticeIds    通知ID列表
     * @param errorMessage 失败原因
     * @return 更新行数
     */
    public int markSendFailedByIds(List<Long> noticeIds, String errorMessage) {
        if (noticeIds == null || noticeIds.isEmpty()) {
            return 0;
        }
        return baseMapper.markSendFailedByIds(noticeIds, errorMessage);
    }

    /**
     * 在发送前冻结最终文本、载荷哈希和实际发送尝试时间。
     *
     * @param noticeIds       通知ID列表
     * @param payloadSnapshot 最终载荷快照
     * @param payloadHash     最终载荷哈希
     * @param attemptedAt     实际发送尝试时间
     */
    public void finalizePayload(List<Long> noticeIds, String payloadSnapshot,
                                String payloadHash, LocalDateTime attemptedAt) {
        if (noticeIds == null || noticeIds.isEmpty()) {
            return;
        }
        baseMapper.finalizePayload(noticeIds, payloadSnapshot, payloadHash, attemptedAt);
    }
}
