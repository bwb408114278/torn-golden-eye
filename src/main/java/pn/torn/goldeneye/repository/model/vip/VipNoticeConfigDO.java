package pn.torn.goldeneye.repository.model.vip;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
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
@NoArgsConstructor
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
     * 启用类型位掩码
     */
    private Integer enableTypes;
    /**
     * 暂停能量到日期
     */
    private LocalDateTime pauseEnergyUntil;
    /**
     * 暂停能量到日期
     */
    private LocalDateTime pauseTravelUntil;

    public VipNoticeConfigDO(long userId, long qqId) {
        this.userId = userId;
        this.qqId = qqId;
        this.enableTypes = 0;
    }

    public boolean isEnabled(List<VipNoticeTypeEnum> typeList) {
        int combinedType = typeList.stream().mapToInt(VipNoticeTypeEnum::getBit)
                .reduce(0, (a, b) -> a | b);
        return (enableTypes & combinedType) != 0;
    }

    public boolean isEnabled(VipNoticeTypeEnum type) {
        return (enableTypes & type.getBit()) != 0;
    }
}