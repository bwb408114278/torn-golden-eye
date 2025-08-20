package pn.torn.goldeneye.torn.model.user.oc;

import lombok.Data;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import pn.torn.goldeneye.base.torn.TornReqParamV2;

/**
 * Torn用户OC请求
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.20
 */
@Data
public class TornUserOcDTO implements TornReqParamV2 {
    @Override
    public String uri() {
        return "/user/organizedcrime";
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