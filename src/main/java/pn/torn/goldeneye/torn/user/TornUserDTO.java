package pn.torn.goldeneye.torn.user;

import lombok.Data;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import pn.torn.goldeneye.base.torn.TornReqParamV2;

import java.util.List;

/**
 * Torn用户请求
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@Data
public class TornUserDTO implements TornReqParamV2 {
    /**
     * 用户ID
     */
    private Long id;

    public TornUserDTO(Long id) {
        this.id = id;
    }

    @Override
    public String uri() {
        return "/user";
    }

    @Override
    public MultiValueMap<String, String> buildReqParam() {
        MultiValueMap<String, String> param = new LinkedMultiValueMap<>(1);
        param.put("id", List.of(id.toString()));
        return param;
    }
}