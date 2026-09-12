package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockNoticeAuditMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.NoticePayloadFinalizeCommand;

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
     * 查询待发送通知,批量获取避免N+1
     *
     * @return 待发送通知列表
     */
    public List<TornStockNoticeAuditDO> selectPendingNotices() {
        return baseMapper.selectPendingNotices();
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
     * 在发送前逐条冻结最终文本、载荷哈希和实际发送尝试时间。
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
     * 判断是否存在待发送(PENDING)通知。
     * <p>
     * 用于运行时门禁:即使轮次总开关关闭,只要存在PENDING通知且正式消息开关允许,仍应投递。
     *
     * @return 存在待发送通知返回true;否则false
     */
    public boolean existsPendingNotices() {
        return baseMapper.existsPendingNotices();
    }
}
