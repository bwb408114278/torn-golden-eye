package pn.torn.goldeneye.torn.model.torn.attack;

import lombok.Data;
import org.springframework.util.StringUtils;

import java.util.Map;

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

    public String getName(Map<Long, String> userNameMap) {
        if (StringUtils.hasText(this.name) && !"Someone".equals(this.name)) {
            return this.name;
        }

        String userName = userNameMap.get(getId());
        return userName == null ? "Someone" : userName;
    }
}