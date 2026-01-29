package pn.torn.goldeneye.repository.model.setting;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDate;

/**
 * VIP订阅表
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.29
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "vip_subscribe", autoResultMap = true)
public class VipSubscribeDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * QQ号
     */
    private Long qqId;
    /**
     * 订阅时长
     */
    private Integer subscribeLength;
    /**
     * 起始日期
     */
    private LocalDate startDate;
    /**
     * 结束日期
     */
    private LocalDate endDate;
}