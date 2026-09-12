package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.NoticePayloadFinalizeCommand;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn股票通知审计数据库访问层
 * <p>
 * 发送入口必须具备数据库级领取与幂等语义:可发送通知的读取、领取、冻结与终态回写全部以
 * {@code send_status} 状态迁移和 {@code claim_token} 为条件,未领取成功者不得调用Bot,
 * 旧领取者不得覆盖新状态。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.07.24
 */
@Mapper
public interface TornStockNoticeAuditMapper extends BaseMapper<TornStockNoticeAuditDO> {

    /**
     * 查询可发送通知(PENDING或可重发的FAILED_RETRYABLE且未达尝试上限)。
     * <p>
     * 一次调度的全部发送均从本查询开始;是否真正可发送由后续原子领取决定,
     * 因此本查询不构成互斥,禁止依据查询结果直接调用Bot。
     *
     * @return 可发送通知列表(按ID升序)
     */
    List<TornStockNoticeAuditDO> selectSendableNotices();

    /**
     * 按换仓关联标识读取该α换仓关联组的完整通知集合(不限发送状态)。
     * <p>
     * 发送前必须能读取关联组的完整通知集合:只依赖当前内存中的PENDING子集无法判断缺腿、
     * 重复腿、字段冲突和一腿已进入终态的部分完成状态。
     *
     * @param rebalanceAssociationId 换仓关联标识(固定格式{@code ALPHA_REBALANCE:{rebalanceDecisionId}})
     * @return 该关联组全部未删除通知(按ID升序);不存在时返回空列表
     */
    List<TornStockNoticeAuditDO> selectByRebalanceAssociationId(@Param("rebalanceAssociationId") String rebalanceAssociationId);

    /**
     * 原子领取一批通知:置为SENDING、写入领取标识与领取时间并累计一次发送尝试。
     * <p>
     * 只有 {@code PENDING}/{@code FAILED_RETRYABLE} 且未达尝试上限的通知可被领取;
     * 返回更新行数,调用方必须校验行数等于期望通知数,行数不足时禁止调用Bot。
     *
     * @param noticeIds  通知ID列表
     * @param claimToken 本次领取标识
     * @return 实际领取行数
     */
    int claimByIds(@Param("noticeIds") List<Long> noticeIds,
                   @Param("claimToken") String claimToken);

    /**
     * 以换仓关联标识为单位原子领取该α换仓关联组仍可发送的腿。
     * <p>
     * α换仓两腿必须以关联组为单位领取,禁止只领取一条腿;已SENT或已达上限的腿不会被领取,
     * 返回实际领取行数由调用方校验。
     *
     * @param rebalanceAssociationId 换仓关联标识
     * @param claimToken             本次领取标识
     * @return 实际领取行数
     */
    int claimByRebalanceAssociationId(@Param("rebalanceAssociationId") String rebalanceAssociationId,
                                      @Param("claimToken") String claimToken);

    /**
     * 将本次领取成功的通知标记为已发送(SENT)。
     * <p>
     * 只能回写本领取者持有的SENDING通知:状态与领取标识必须同时匹配,
     * 旧领取者或被恢复的通知不会被覆盖。
     *
     * @param noticeIds  通知ID列表
     * @param claimToken 本次领取标识
     * @return 更新行数
     */
    int markSentByIds(@Param("noticeIds") List<Long> noticeIds,
                      @Param("claimToken") String claimToken);

    /**
     * 将本次领取成功的通知标记为发送失败。
     * <p>
     * 未达总尝试上限写入FAILED_RETRYABLE(后续调度自动重发),达到上限写入FAILED_FINAL(终态);
     * 只能回写本领取者持有的SENDING通知。
     *
     * @param noticeIds    通知ID列表
     * @param claimToken   本次领取标识
     * @param errorMessage 失败原因
     * @return 更新行数
     */
    int markSendFailedByIds(@Param("noticeIds") List<Long> noticeIds,
                            @Param("claimToken") String claimToken,
                            @Param("errorMessage") String errorMessage);

    /**
     * 将不可自动重发的通知直接置为最终失败(FAILED_FINAL)人工核验终态。
     * <p>
     * 用于关联批次不存在、α换仓关联组异常等不可解释状态:不调用Bot,也不进入自动重发。
     *
     * @param noticeIds    通知ID列表
     * @param errorMessage 人工核验原因
     * @return 更新行数
     */
    int markFinalByIds(@Param("noticeIds") List<Long> noticeIds,
                       @Param("errorMessage") String errorMessage);

    /**
     * 释放本次领取:把本领取标识持有且仍未回写的SENDING通知退回可重发/最终失败状态。
     * <p>
     * 领取不完整(并发流程已持有部分通知)时调用,保证未调用Bot的通知不停留在SENDING等待租约;
     * 已回写SENT/终态的通知不会被本语句影响。
     *
     * @param claimToken 本次领取标识
     * @return 释放的通知行数
     */
    int releaseClaim(@Param("claimToken") String claimToken);

    /**
     * 在发送前逐条冻结最终文本与载荷哈希。
     * <p>
     * 每条通知业务payload不同,必须逐条更新,禁止用一份payload覆盖整个noticeIds集合;
     * 只能冻结本领取者持有的SENDING通知(状态与领取标识同时匹配)。
     *
     * @param commands 逐条通知的最终payload冻结命令
     * @return 实际更新行数
     */
    int finalizePayload(@Param("commands") List<NoticePayloadFinalizeCommand> commands);

    /**
     * 恢复领取租约超时仍未回写的通知。
     * <p>
     * 领取成功者进程崩溃或回写异常时通知会停留在SENDING:超过租约后按"结果未知"处理,
     * 未达上限恢复为FAILED_RETRYABLE等待自动重发,达到上限恢复为FAILED_FINAL终态。
     *
     * @param staleBefore 租约截止时间,领取时间早于该值的SENDING通知视为失效
     * @return 恢复的通知行数
     */
    int recoverStaleClaims(@Param("staleBefore") LocalDateTime staleBefore);

    /**
     * 判断是否存在待发送或可重发的通知。
     *
     * @return 存在可发送通知(含待恢复的SENDING)返回true;否则false
     */
    boolean existsSendableNotices();
}
