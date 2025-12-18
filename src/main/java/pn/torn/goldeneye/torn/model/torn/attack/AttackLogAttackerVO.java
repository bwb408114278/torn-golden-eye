package pn.torn.goldeneye.torn.model.torn.attack;

import lombok.Data;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;

/**
 * 战斗Log攻击方响应参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.17
 */
@Data
public class AttackLogAttackerVO {
    /**
     * 攻方ID
     */
    private Long id;
    /**
     * 攻方昵称
     */
    private String name;
    /**
     * 使用物品
     */
    private AttackLogItem item;

    public long getId() {
        return this.id == null ? 0L : this.id;
    }

    public String getName(TornUserManager userManager) {
        if (StringUtils.hasText(this.name)) {
            return this.name;
        }

        TornUserDO user = userManager.getUserMap().get(getId());
        return user == null ? "Someone" : user.getNickname();
    }
}