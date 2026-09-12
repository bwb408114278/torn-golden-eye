package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.NoticePayloadFinalizeCommand;

import java.util.List;

/**
 * Torn股票通知审计数据库访问层
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.07.24
 */
@Mapper
public interface TornStockNoticeAuditMapper extends BaseMapper<TornStockNoticeAuditDO> {

    /**
     * 查询待发送通知
     *
     * @return 待发送通知列表
     */
    List<TornStockNoticeAuditDO> selectPendingNotices();

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
     * 批量标记无关联批次的通知为FAILED。
     *
     * @param noticeIds    通知ID列表
     * @param errorMessage 错误信息
     * @return 更新行数
     */
    int markFailedByIds(@Param("noticeIds") List<Long> noticeIds,
                        @Param("errorMessage") String errorMessage);

    /**
     * 批量标记通知发送成功。
     *
     * @param noticeIds 通知ID列表
     * @return 更新行数
     */
    int markSentByIds(@Param("noticeIds") List<Long> noticeIds);

    /**
     * 批量标记通知发送失败。
     *
     * @param noticeIds    通知ID列表
     * @param errorMessage 失败原因
     * @return 更新行数
     */
    int markSendFailedByIds(@Param("noticeIds") List<Long> noticeIds,
                            @Param("errorMessage") String errorMessage);

    /**
     * 在发送前逐条冻结最终文本、载荷哈希和实际发送尝试时间。
     * <p>
     * 每条通知业务payload不同,必须逐条更新,禁止用一份payload覆盖整个noticeIds集合。
     *
     * @param commands 逐条通知的最终payload冻结命令
     * @return 实际更新行数
     */
    int finalizePayload(@Param("commands") List<NoticePayloadFinalizeCommand> commands);

    /**
     * 判断是否存在待发送(PENDING)通知。
     *
     * @return 存在待发送通知返回true;否则false
     */
    boolean existsPendingNotices();
}
