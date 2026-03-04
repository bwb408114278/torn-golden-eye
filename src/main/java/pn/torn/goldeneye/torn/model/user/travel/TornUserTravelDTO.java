package pn.torn.goldeneye.torn.model.user.travel;

import lombok.Data;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import pn.torn.goldeneye.base.torn.TornReqParamV2;

/**
 * Torn用户旅行请求
 *
 * @author Bai
 * @version 1.0.0
 * @since 2026.03.04
 */
@Data
public class TornUserTravelDTO implements TornReqParamV2 {
    @Override
    public String uri() {
        return "/user/travel";
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