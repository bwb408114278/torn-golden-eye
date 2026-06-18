package pn.torn.goldeneye.torn.model.faction.revive;

import lombok.AllArgsConstructor;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import pn.torn.goldeneye.base.torn.TornReqParamV2;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn帮派复活请求
 *
 * @author Bai
 * @version 1.2.3
 * @since 2026.06.17
 */
@AllArgsConstructor
public class TornFactionReviveDTO implements TornReqParamV2 {
    /**
     * 查询起始时间戳
     */
    private final LocalDateTime from;
    /**
     * 查询结束时间戳
     */
    private final LocalDateTime to;
    /**
     * 限制行数
     */
    private final int limit;

    @Override
    public String uri() {
        return "/faction";
    }

    @Override
    public boolean needFactionAccess() {
        return true;
    }

    @Override
    public MultiValueMap<String, String> buildReqParam() {
        MultiValueMap<String, String> param = new LinkedMultiValueMap<>(5);
        param.put("selections", List.of("revives"));
        param.put("from", List.of(DateTimeUtils.convertToShortTimestamp(from).toString()));
        param.put("to", List.of(DateTimeUtils.convertToShortTimestamp(to).toString()));
        param.put("limit", List.of(String.valueOf(this.limit)));
        param.put("sort", List.of("ASC"));

        return param;
    }
}
