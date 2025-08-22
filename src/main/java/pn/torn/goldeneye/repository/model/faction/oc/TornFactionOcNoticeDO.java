package pn.torn.goldeneye.repository.model.faction.oc;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

/**
 * Torn OC 跳过表
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.30
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_faction_oc_notice", autoResultMap = true)
public class TornFactionOcNoticeDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 级别
     */
    private Integer rank;
    /**
     * 是否提醒
     */
    private Boolean hasNotice;
    /**
     * 是否咸鱼队
     */
    private Boolean hasSkip;
}