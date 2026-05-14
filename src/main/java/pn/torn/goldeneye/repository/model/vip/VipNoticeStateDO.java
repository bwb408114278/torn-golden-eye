package pn.torn.goldeneye.repository.model.vip;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.constants.bot.enums.VipNoticeTypeEnum;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDateTime;

/**
 * VIP提醒状态表
 *
 * @author Bai
 * @version 1.1.1
 * @since 2026.05.11
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "vip_notice_state", autoResultMap = true)
@NoArgsConstructor
public class VipNoticeStateDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 通知类型
     */
    private Integer noticeType;
    /**
     * 上次检查时间
     */
    private LocalDateTime lastCheckTime;
    /**
     * 剩余秒数
     */
    private Long lastValue;

    public VipNoticeStateDO(Long userId, VipNoticeTypeEnum noticeType) {
        this.userId = userId;
        this.noticeType = noticeType.getBit();
        this.lastCheckTime = LocalDateTime.now();
        this.lastValue = 0L;
    }
}