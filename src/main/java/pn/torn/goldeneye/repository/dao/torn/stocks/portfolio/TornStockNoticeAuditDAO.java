package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockNoticeAuditMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.NoticePayloadFinalizeCommand;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn股票通知审计持久层类
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.07.24
 */
@Repository
public class TornStockNoticeAuditDAO extends ServiceImpl<TornStockNoticeAuditMapper, TornStockNoticeAuditDO> {

    /**
     * 查询可发送通知(PENDING或未达尝试上限的FAILED_RETRYABLE)。
     * <p>
     * 本查询只用于生成本轮待发送集合,不构成互斥:是否可发送必须由 {@link #claimByIds} 的原子领取结果决定。
     *
     * @return 可发送通知列表
     */
    public List<TornStockNoticeAuditDO> selectSendableNotices() {
        return baseMapper.selectSendableNotices();
    }

    /**
     * 按换仓关联标识读取该α换仓关联组的完整通知集合(不限发送状态)。
     * <p>
     * α换仓两腿必须作为一条原子消息统一冻结与发送,因此发送前读取的是关联组完整通知集合,
     * 而不是仅当前批次的PENDING子集。
     *
     * @param rebalanceAssociationId 换仓关联标识
     * @return 该关联组全部未删除通知(按ID升序);不存在时返回空列表
     */
    public List<TornStockNoticeAuditDO> selectByRebalanceAssociationId(String rebalanceAssociationId) {
        if (rebalanceAssociationId == null || rebalanceAssociationId.isBlank()) {
            return List.of();
        }
        return baseMapper.selectByRebalanceAssociationId(rebalanceAssociationId);
    }

    /**
     * 原子领取一批通知。
     *
     * @param noticeIds  通知ID列表
     * @param claimToken 本次领取标识
     * @return 实际领取行数;入参为空时返回0
     */
    public int claimByIds(List<Long> noticeIds, String claimToken) {
        if (noticeIds == null || noticeIds.isEmpty() || claimToken == null || claimToken.isBlank()) {
            return 0;
        }
        return baseMapper.claimByIds(noticeIds, claimToken);
    }

    /**
     * 以换仓关联标识为单位原子领取该关联组仍可发送的腿。
     *
     * @param rebalanceAssociationId 换仓关联标识
     * @param claimToken             本次领取标识
     * @return 实际领取行数;入参为空时返回0
     */
    public int claimByRebalanceAssociationId(String rebalanceAssociationId, String claimToken) {
        if (rebalanceAssociationId == null || rebalanceAssociationId.isBlank()
                || claimToken == null || claimToken.isBlank()) {
            return 0;
        }
        return baseMapper.claimByRebalanceAssociationId(rebalanceAssociationId, claimToken);
    }

    /**
     * 批量标记通知发送成功(SENT)。
     *
     * @param noticeIds  通知ID列表
     * @param claimToken 本次领取标识
     * @return 更新行数
     */
    public int markSentByIds(List<Long> noticeIds, String claimToken) {
        if (noticeIds == null || noticeIds.isEmpty()) {
            return 0;
        }
        return baseMapper.markSentByIds(noticeIds, claimToken);
    }

    /**
     * 批量标记通知发送失败(未达上限为FAILED_RETRYABLE,达到上限为FAILED_FINAL)。
     *
     * @param noticeIds    通知ID列表
     * @param claimToken   本次领取标识
     * @param errorMessage 失败原因
     * @return 更新行数
     */
    public int markSendFailedByIds(List<Long> noticeIds, String claimToken, String errorMessage) {
        if (noticeIds == null || noticeIds.isEmpty()) {
            return 0;
        }
        return baseMapper.markSendFailedByIds(noticeIds, claimToken, errorMessage);
    }

    /**
     * 批量将不可自动重发的通知置为最终失败(FAILED_FINAL)人工核验终态。
     *
     * @param noticeIds    通知ID列表
     * @param errorMessage 人工核验原因
     * @return 更新行数
     */
    public int markFinalByIds(List<Long> noticeIds, String errorMessage) {
        if (noticeIds == null || noticeIds.isEmpty()) {
            return 0;
        }
        return baseMapper.markFinalByIds(noticeIds, errorMessage);
    }

    /**
     * 释放本次领取持有的未回写SENDING通知。
     *
     * @param claimToken 本次领取标识
     * @return 释放的通知行数
     */
    public int releaseClaim(String claimToken) {
        if (claimToken == null || claimToken.isBlank()) {
            return 0;
        }
        return baseMapper.releaseClaim(claimToken);
    }

    /**
     * 在发送前逐条冻结最终文本和载荷哈希。
     * <p>
     * 同一合并消息可能对应多条通知,每条通知业务payload不同,因此必须逐条冻结,
     * 禁止用一份payload覆盖整个noticeIds集合。返回实际更新行数,调用方必须校验
     * 更新行数等于通知数,否则不得调用Bot发送不可审计消息。
     *
     * @param commands 逐条通知的最终payload冻结命令
     * @return 实际更新行数
     */
    public int finalizePayload(List<NoticePayloadFinalizeCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return 0;
        }
        return baseMapper.finalizePayload(commands);
    }

    /**
     * 恢复领取租约超时仍未回写的通知。
     *
     * @param staleBefore 租约截止时间
     * @return 恢复的通知行数
     */
    public int recoverStaleClaims(LocalDateTime staleBefore) {
        if (staleBefore == null) {
            return 0;
        }
        return baseMapper.recoverStaleClaims(staleBefore);
    }

    /**
     * 判断是否存在待发送、可重发或待恢复的通知。
     * <p>
     * 用于运行时门禁:即使轮次总开关关闭,只要存在可发送通知且正式消息开关允许,仍应投递。
     *
     * @return 存在可发送通知返回true;否则false
     */
    public boolean existsSendableNotices() {
        return baseMapper.existsSendableNotices();
    }
}
