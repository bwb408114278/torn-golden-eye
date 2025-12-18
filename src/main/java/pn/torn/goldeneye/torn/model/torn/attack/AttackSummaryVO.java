package pn.torn.goldeneye.torn.model.torn.attack;

import lombok.Data;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.repository.model.torn.TornAttackLogSummaryDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;

/**
 * 战斗Log统计响应参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.17
 */
@Data
public class AttackSummaryVO {
    /**
     * 用户ID
     */
    private Long id;
    /**
     * 用户昵称
     */
    private String name;
    /**
     * 击中数
     */
    private int hits;
    /**
     * 失手数
     */
    private int misses;
    /**
     * 造成伤害
     */
    private long damage;

    public TornAttackLogSummaryDO convert2DO(String logId, TornUserManager userManager) {
        TornAttackLogSummaryDO summary = new TornAttackLogSummaryDO();
        summary.setLogId(logId);
        summary.setUserId(this.id == null ? 0L : this.id);
        summary.setHits(this.hits);
        summary.setMisses(this.misses);
        summary.setDamage(this.damage);

        if (StringUtils.hasText(this.name)) {
            summary.setNickname(this.name);
        } else {
            TornUserDO user = userManager.getUserMap().get(getId());
            summary.setNickname(user == null ? "Someone" : user.getNickname());
        }

        return summary;
    }
}