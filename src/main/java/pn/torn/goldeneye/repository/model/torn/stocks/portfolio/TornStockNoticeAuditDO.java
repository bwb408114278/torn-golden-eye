package pn.torn.goldeneye.repository.model.torn.stocks.portfolio;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Torn股票通知审计表
 * <p>
 * 记录每条策略通知的调度、载荷、发送状态与重试信息,确保通知可追溯、
 * 可重放、可去重,支撑消息通知的可靠投递与审计。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_stock_notice_audit", autoResultMap = true)
public class TornStockNoticeAuditDO extends BaseDO {
    /**
     * 主键ID
     */
    private Long id;
    /**
     * 通知编号(业务唯一编号,便于查询与去重)
     */
    private String noticeNo;
    /**
     * 关联批次ID(批次相关通知时填充)
     */
    private Long batchId;
    /**
     * 通知类型(如SIGNAL_BUY/SELL_ALERT/DAILY_SUMMARY)
     */
    private String noticeType;
    /**
     * 计划发送的轮次时间(定时通知的预期触发时间)
     */
    private LocalDateTime scheduledRoundTime;
    /**
     * 摘要日期(日报类通知归属的自然日)
     */
    private LocalDate summaryDate;
    /**
     * 接收群组ID(通知投递的目标群组)
     */
    private Long groupId;
    /**
     * 载荷哈希(通知内容的去重指纹,相同哈希避免重复发送)
     */
    private String payloadHash;
    /**
     * 载荷快照(JSON文本,通知的完整消息体)
     */
    private String payloadSnapshot;
    /**
     * 发送状态(PENDING/SENDING/SUCCESS/FAILED)
     */
    private String sendStatus;
    /**
     * 发送尝试次数(累计重试次数)
     */
    private Integer sendAttemptCount;
    /**
     * 最近一次尝试发送时间
     */
    private LocalDateTime attemptedAt;
    /**
     * 成功发送时间
     */
    private LocalDateTime sentAt;
    /**
     * 错误信息(发送失败时记录的异常摘要)
     */
    private String errorMessage;
    /**
     * 消息规则版本(生成通知内容所用的规则版本)
     */
    private String messageRuleVersion;
}
