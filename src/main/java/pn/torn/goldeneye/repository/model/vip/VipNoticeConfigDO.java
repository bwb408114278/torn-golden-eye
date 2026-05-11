package pn.torn.goldeneye.repository.model.vip;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.constants.bot.enums.VipNoticeTypeEnum;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * VIP提醒设置表
 *
 * @author Bai
 * @version 1.1.1
 * @since 2026.05.11
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "vip_notice_config", autoResultMap = true)
public class VipNoticeConfigDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * QQ ID
     */
    private Long qqId;
    /**
     * 禁用类型位掩码
     */
    private Integer disabledTypes;
    /**
     * 暂停到日期
     */
    private LocalDateTime pauseUntil;

    public boolean isDisabled(List<VipNoticeTypeEnum> typeList) {
        int combinedType = typeList.stream().mapToInt(VipNoticeTypeEnum::getBit)
                .reduce(0, (a, b) -> a | b);
        return (disabledTypes & combinedType) != 0;
    }

    /**
     * 仅ENERGY和TRAVEL受暂停影响
     */
    public boolean isPaused(List<VipNoticeTypeEnum> typeList) {
        if (!typeList.contains(VipNoticeTypeEnum.ENERGY) && !typeList.contains(VipNoticeTypeEnum.TRAVEL)) {
            return false;
        }

        return pauseUntil != null && !LocalDateTime.now().isAfter(pauseUntil);
    }
}