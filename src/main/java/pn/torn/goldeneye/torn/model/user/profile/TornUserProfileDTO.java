package pn.torn.goldeneye.torn.model.user.profile;

import lombok.RequiredArgsConstructor;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import pn.torn.goldeneye.base.torn.TornReqParamV2;

/**
 * Torn用户Profile请求
 *
 * @author Bai
 * @version 1.2.3
 * @since 2026.06.17
 */
@RequiredArgsConstructor
public class TornUserProfileDTO implements TornReqParamV2 {
    /**
     * 用户ID
     */
    private final long userId;

    @Override
    public String uri() {
        return "/user/" + userId + "/profile";
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
