package pn.torn.goldeneye.repository.model.torn.stocks.portfolio;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.configuration.db.JsonbTypeHandler;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Torn股票通知审计表
 * <p>
 * 保存正式买卖和每日摘要的中文消息快照、发送领取事实与最终发送结果。
 * 它是最小审计表,不是高可用Outbox实现:不解析NapCat ACK,不建设第二套消息平台;
 * 自动重发在既有通知审计、payload冻结和发送状态下完成,总尝试次数固定3次。
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
     * 载荷哈希(最终完整payload规范化JSON的SHA-256摘要,64位小写十六进制)
     */
    private String payloadHash;
    /**
     * 载荷快照(JSON文本,不可丢失的业务字段、最终中文文本和冻结时间的完整快照)
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String payloadSnapshot;
    /**
     * 发送状态(PENDING/SENDING/SENT/FAILED_RETRYABLE/FAILED_FINAL;FAILED为历史遗留终态)
     */
    private String sendStatus;
    /**
     * 发送尝试次数(领取时累计,上限3次,达到上限进入FAILED_FINAL)
     */
    private Integer sendAttemptCount;
    /**
     * 发送领取标识(仅领取者可回写终态,防止旧领取者覆盖新状态)
     */
    private String claimToken;
    /**
     * 发送领取时间(超过租约仍未回写时按结果未知恢复为可重发)
     */
    private LocalDateTime claimTime;
    /**
     * 最近一次领取(Bot调用)时间,领取时写入
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
