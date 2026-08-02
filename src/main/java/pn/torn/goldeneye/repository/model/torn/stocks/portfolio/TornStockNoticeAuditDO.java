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
 * 保存正式买卖和每日摘要的中文消息快照及本期一次发送结果。
 * 它是最小审计表,不是高可用Outbox实现:本期不自动重试、不解析NapCat ACK,
 * 仅证明系统生成了唯一通知并执行了一次发送调用。
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
     * 发送状态(PENDING/SENT/FAILED)
     */
    private String sendStatus;
    /**
     * 发送尝试次数(本期固定最多1次,保留扩展字段)
     */
    private Integer sendAttemptCount;
    /**
     * 最近一次尝试发送时间(最终payload冻结成功后才调用Bot)
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
