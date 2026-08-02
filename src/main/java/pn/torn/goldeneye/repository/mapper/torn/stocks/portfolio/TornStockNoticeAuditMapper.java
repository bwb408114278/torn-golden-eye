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
 * @version 1.2.12
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
