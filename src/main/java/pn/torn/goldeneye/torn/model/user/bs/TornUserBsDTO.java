package pn.torn.goldeneye.torn.model.user.bs;

import lombok.Data;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import pn.torn.goldeneye.base.torn.TornReqParamV2;

/**
 * Torn用户BS请求
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.10.27
 */
@Data
public class TornUserBsDTO implements TornReqParamV2 {
    @Override
    public String uri() {
        return "/user/battlestats";
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