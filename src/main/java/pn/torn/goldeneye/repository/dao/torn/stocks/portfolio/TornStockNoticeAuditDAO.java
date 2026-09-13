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
 * <p>
 * 本层只做入参校验与Mapper透传,不吞掉数据库异常:领取、冻结与回写异常属于"结果未知",
 * 必须由上层进入组级恢复或人工核验路径,而不是在本层被静默转为0行成功语义。
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
     * @param noticeIds   通知ID列表
     * @param claimToken  本次领取标识
     * @param businessNow 本次发送编排的统一业务时间
     * @return 实际领取行数;入参为空时返回0
     */
    public int claimByIds(List<Long> noticeIds, String claimToken, LocalDateTime businessNow) {
        if (noticeIds == null || noticeIds.isEmpty() || claimToken == null || claimToken.isBlank()
                || businessNow == null) {
            return 0;
        }
        return baseMapper.claimByIds(noticeIds, claimToken, businessNow);
    }

    /**
     * 以换仓关联标识为单位原子领取该关联组的两腿。
     *
     * @param rebalanceAssociationId 换仓关联标识
     * @param claimToken             本次领取标识
     * @param businessNow            本次发送编排的统一业务时间
     * @return 实际领取行数;完整组为2,否则为0;入参为空时返回0
     */
    public int claimByRebalanceAssociationId(String rebalanceAssociationId, String claimToken,
                                             LocalDateTime businessNow) {
        if (rebalanceAssociationId == null || rebalanceAssociationId.isBlank()
                || claimToken == null || claimToken.isBlank() || businessNow == null) {
            return 0;
        }
        return baseMapper.claimByRebalanceAssociationId(rebalanceAssociationId, claimToken, businessNow);
    }

    /**
     * 批量标记通知发送成功(SENT)。
     *
     * @param noticeIds   通知ID列表
     * @param claimToken  本次领取标识
     * @param businessNow 本次发送编排的统一业务时间
     * @return 更新行数
     */
    public int markSentByIds(List<Long> noticeIds, String claimToken, LocalDateTime businessNow) {
        if (noticeIds == null || noticeIds.isEmpty() || businessNow == null) {
            return 0;
        }
        return baseMapper.markSentByIds(noticeIds, claimToken, businessNow);
    }

    /**
     * 批量标记通知发送失败(未达上限为FAILED_RETRYABLE,达到上限为FAILED_FINAL)。
     *
     * @param noticeIds    通知ID列表
     * @param claimToken   本次领取标识
     * @param errorMessage 失败原因
     * @param businessNow  本次发送编排的统一业务时间
     * @return 更新行数
     */
    public int markSendFailedByIds(List<Long> noticeIds, String claimToken, String errorMessage,
                                   LocalDateTime businessNow) {
        if (noticeIds == null || noticeIds.isEmpty() || businessNow == null) {
            return 0;
        }
        return baseMapper.markSendFailedByIds(noticeIds, claimToken, errorMessage, businessNow);
    }

    /**
     * 批量将不可自动重发的通知置为最终失败(FAILED_FINAL)人工核验终态。
     *
     * @param noticeIds    通知ID列表
     * @param errorMessage 人工核验原因
     * @param businessNow  本次发送编排的统一业务时间
     * @return 更新行数
     */
    public int markFinalByIds(List<Long> noticeIds, String errorMessage, LocalDateTime businessNow) {
        if (noticeIds == null || noticeIds.isEmpty() || businessNow == null) {
            return 0;
        }
        return baseMapper.markFinalByIds(noticeIds, errorMessage, businessNow);
    }

    /**
     * 释放本次领取持有的未回写SENDING通知。
     *
     * @param claimToken  本次领取标识
     * @param businessNow 本次发送编排的统一业务时间
     * @return 释放的通知行数
     */
    public int releaseClaim(String claimToken, LocalDateTime businessNow) {
        if (claimToken == null || claimToken.isBlank() || businessNow == null) {
            return 0;
        }
        return baseMapper.releaseClaim(claimToken, businessNow);
    }

    /**
     * 在发送前逐条冻结最终文本和载荷哈希。
     * <p>
     * 同一合并消息可能对应多条通知,每条通知业务payload不同,因此必须逐条冻结,
     * 禁止用一份payload覆盖整个noticeIds集合。返回实际更新行数,调用方必须校验
     * 更新行数等于通知数,否则不得调用Bot发送不可审计消息。
     *
     * @param commands    逐条通知的最终payload冻结命令
     * @param businessNow 本次发送编排的统一业务时间
     * @return 实际更新行数
     */
    public int finalizePayload(List<NoticePayloadFinalizeCommand> commands, LocalDateTime businessNow) {
        if (commands == null || commands.isEmpty() || businessNow == null) {
            return 0;
        }
        return baseMapper.finalizePayload(commands, businessNow);
    }

    /**
     * 恢复领取租约超时仍未回写的通知。
     *
     * @param staleBefore 租约截止时间
     * @param businessNow 本次发送编排的统一业务时间
     * @return 恢复的通知行数
     */
    public int recoverStaleClaims(LocalDateTime staleBefore, LocalDateTime businessNow) {
        if (staleBefore == null || businessNow == null) {
            return 0;
        }
        return baseMapper.recoverStaleClaims(staleBefore, businessNow);
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

    /**
     * 组级原子成功回写:两腿与组状态在同一SQL语义内进入SENT。
     *
     * @param rebalanceAssociationId 换仓关联标识
     * @param claimToken             本次领取标识
     * @param businessNow            本次发送编排的统一业务时间
     * @return 实际更新行数;完整组为2,否则为0
     */
    public int markRebalanceGroupSent(String rebalanceAssociationId, String claimToken,
                                      LocalDateTime businessNow) {
        if (rebalanceAssociationId == null || rebalanceAssociationId.isBlank()
                || claimToken == null || claimToken.isBlank() || businessNow == null) {
            return 0;
        }
        return baseMapper.markRebalanceGroupSent(rebalanceAssociationId, claimToken, businessNow);
    }

    /**
     * 组级原子失败回写:两腿与组状态在同一次数据库语义内得到同一失败结果。
     *
     * @param rebalanceAssociationId 换仓关联标识
     * @param claimToken             本次领取标识
     * @param errorMessage           实际发送失败原因
     * @param businessNow            本次发送编排的统一业务时间
     * @return 实际更新行数;完整组为2,否则为0
     */
    public int markRebalanceGroupFailed(String rebalanceAssociationId, String claimToken,
                                        String errorMessage, LocalDateTime businessNow) {
        if (rebalanceAssociationId == null || rebalanceAssociationId.isBlank()
                || claimToken == null || claimToken.isBlank() || businessNow == null) {
            return 0;
        }
        return baseMapper.markRebalanceGroupFailed(rebalanceAssociationId, claimToken, errorMessage, businessNow);
    }

    /**
     * 组级异常终态收敛:把关联组内全部腿统一写入FAILED_FINAL/INCONSISTENT人工核验终态。
     *
     * @param rebalanceAssociationId 换仓关联标识
     * @param groupStatus            目标组状态(FAILED_FINAL或INCONSISTENT)
     * @param groupError             组级异常原因
     * @param businessNow            本次发送编排的统一业务时间
     * @return 实际更新行数;组不存在或已确认成功时为0
     */
    public int convergeRebalanceGroup(String rebalanceAssociationId, String groupStatus, String groupError,
                                      LocalDateTime businessNow) {
        if (rebalanceAssociationId == null || rebalanceAssociationId.isBlank()
                || groupStatus == null || groupStatus.isBlank() || businessNow == null) {
            return 0;
        }
        return baseMapper.convergeRebalanceGroup(rebalanceAssociationId, groupStatus, groupError, businessNow);
    }
}
