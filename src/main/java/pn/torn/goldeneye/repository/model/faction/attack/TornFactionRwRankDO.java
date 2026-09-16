package pn.torn.goldeneye.repository.model.faction.attack;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.math.BigDecimal;

/**
 * RW真赛战神榜名次结算表
 *
 * <p>一场战争在结束后按战神榜口径结算一次，表内保存结算当时的昵称与输出评分快照；
 * 重结算采用物理删除后重插，避免逻辑删除行占用唯一索引。</p>
 *
 * @author Bai
 * @version 1.6.4
 * @since 2026.09.16
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_faction_rw_rank", autoResultMap = true)
public class TornFactionRwRankDO extends BaseDO {
    /**
     * 主键ID（雪花）
     */
    private Long id;
    /**
     * 所属RW ID
     */
    private Long rwId;
    /**
     * 成员Torn用户ID
     */
    private Long userId;
    /**
     * 结算时昵称快照
     */
    private String nickname;
    /**
     * 战神榜名次（输出评分降序行号）
     */
    private Integer rankNum;
    /**
     * 输出评分快照
     */
    private BigDecimal damageScore;
}
