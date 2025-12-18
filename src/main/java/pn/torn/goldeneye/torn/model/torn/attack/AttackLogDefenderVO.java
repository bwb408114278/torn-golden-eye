package pn.torn.goldeneye.torn.model.torn.attack;

import lombok.Data;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;

/**
 * 战斗Log防守方响应参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.17
 */
@Data
public class AttackLogDefenderVO {
    /**
     * 防守方ID
     */
    private Long id;
    /**
     * 防守方昵称
     */
    private String name;

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