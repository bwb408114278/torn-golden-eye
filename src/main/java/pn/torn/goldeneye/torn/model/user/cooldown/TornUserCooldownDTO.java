package pn.torn.goldeneye.torn.model.user.cooldown;

import lombok.Data;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import pn.torn.goldeneye.base.torn.TornReqParamV2;

/**
 * Torn用户冷却请求
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.12
 */
@Data
public class TornUserCooldownDTO implements TornReqParamV2 {
    @Override
    public String uri() {
        return "/user/cooldowns";
    }

    @Override
    public boolean needFactionAccess() {
        return false;
    }

    @Override
    public MultiValueMap<String, String> buildReqParam() {
        return new LinkedMultiValueMap<>(1);
    }
}