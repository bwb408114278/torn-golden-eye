package pn.torn.goldeneye.repository.model.faction.oc;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

/**
 * Torn OC 用户表
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.29
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_faction_oc_user", autoResultMap = true)
public class TornFactionOcUserDO extends BaseDO {
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
     * OC名称
     */
    private String ocName;
    /**
     * 岗位
     */
    private String position;
    /**
     * 成功率
     */
    private Integer passRate;
}