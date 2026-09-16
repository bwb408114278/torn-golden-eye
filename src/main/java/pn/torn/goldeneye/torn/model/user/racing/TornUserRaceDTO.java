package pn.torn.goldeneye.torn.model.user.racing;

import lombok.Data;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import pn.torn.goldeneye.base.torn.TornReqParamV2;
import pn.torn.goldeneye.constants.torn.RacingConstants;

import java.util.List;

/**
 * Torn用户赛车列表请求
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.05.11
 */
@Data
public class TornUserRaceDTO implements TornReqParamV2 {
    /**
     * 拉取条数，接口上限为100
     */
    private int limit;

    /**
     * 以指定拉取条数构建请求。
     *
     * @param limit 拉取条数
     */
    public TornUserRaceDTO(int limit) {
        this.limit = limit;
    }

    @Override
    public String uri() {
        return "/user/races";
    }

    @Override
    public boolean needFactionAccess() {
        return false;
    }

    @Override
    public MultiValueMap<String, String> buildReqParam() {
        MultiValueMap<String, String> param = new LinkedMultiValueMap<>(3);
        param.put("cat", List.of(RacingConstants.RACE_CATEGORY_CUSTOM));
        param.put("sort", List.of("DESC"));
        param.put("limit", List.of(String.valueOf(limit)));
        return param;
    }
}
