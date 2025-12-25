package pn.torn.goldeneye.torn.model.faction.rw;

import lombok.Data;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import pn.torn.goldeneye.base.torn.TornReqParamV2;

/**
 * Torn帮派Rw请求
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.25
 */
@Data
public class TornFactionRwDTO implements TornReqParamV2 {
    @Override
    public String uri() {
        return "/faction/rankedwars";
    }

    @Override
    public boolean needFactionAccess() {
        return false;
    }

    @Override
    public MultiValueMap<String, String> buildReqParam() {
        return new LinkedMultiValueMap<>(0);
    }
}