package pn.torn.goldeneye.torn.model.faction.member;

import lombok.Data;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import pn.torn.goldeneye.base.torn.TornReqParamV2;

/**
 * Torn帮派成员请求
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.04
 */
@Data
public class TornFactionMemberDTO implements TornReqParamV2 {
    /**
     * 帮派ID
     */
    private Long id;

    public TornFactionMemberDTO(Long id) {
        this.id = id;
    }

    @Override
    public String uri() {
        return "/faction/" + this.id + "/members";
    }

    @Override
    public MultiValueMap<String, String> buildReqParam() {
        return new LinkedMultiValueMap<>(0);
    }
}