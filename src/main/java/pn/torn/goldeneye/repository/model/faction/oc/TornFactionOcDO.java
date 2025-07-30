package pn.torn.goldeneye.repository.model.faction.oc;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDateTime;

/**
 * Torn OC表
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.29
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_faction_oc", autoResultMap = true)
public class TornFactionOcDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 级别
     */
    private Integer rank;
    /**
     * 状态
     */
    private String status;
    /**
     * 准备时间
     */
    private LocalDateTime readyTime;
    /**
     * 上级OC ID
     */
    private Long previousOcId;
}