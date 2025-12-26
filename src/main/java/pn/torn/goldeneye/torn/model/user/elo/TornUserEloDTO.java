package pn.torn.goldeneye.torn.model.user.elo;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import pn.torn.goldeneye.base.torn.TornReqParamV2;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn用户Elo请求
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.24
 */
@Data
@AllArgsConstructor
public class TornUserEloDTO implements TornReqParamV2 {
    /**
     * 用户ID
     */
    private long userId;
    /**
     * 时间戳
     */
    private LocalDateTime timestamp;

    @Override
    public String uri() {
        return "/user/" + this.userId + "/personalstats";
    }

    @Override
    public boolean needFactionAccess() {
        return false;
    }

    @Override
    public MultiValueMap<String, String> buildReqParam() {
        MultiValueMap<String, String> param = new LinkedMultiValueMap<>(2);
        param.put("stat", List.of("elo"));
        param.put("timestamp", List.of(DateTimeUtils.convertToShortTimestamp(this.timestamp).toString()));

        return param;
    }
}