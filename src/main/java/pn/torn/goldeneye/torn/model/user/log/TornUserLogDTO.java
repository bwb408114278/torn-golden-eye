package pn.torn.goldeneye.torn.model.user.log;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import pn.torn.goldeneye.base.torn.TornReqParamV2;
import pn.torn.goldeneye.constants.torn.enums.user.TornUserLogTypeEnum;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn用户日志请求
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.30
 */
@Data
@AllArgsConstructor
public class TornUserLogDTO implements TornReqParamV2 {
    /**
     * log子分类
     */
    private TornUserLogTypeEnum log;
    /**
     * 开始时间
     */
    private LocalDateTime from;
    /**
     * 结束时间
     */
    private LocalDateTime to;
    /**
     * 每页行数
     */
    private Integer limit;

    @Override
    public String uri() {
        return "/user/log";
    }

    @Override
    public boolean needFactionAccess() {
        return false;
    }

    @Override
    public MultiValueMap<String, String> buildReqParam() {
        MultiValueMap<String, String> param = new LinkedMultiValueMap<>(3);
        param.put("log", List.of(String.valueOf(this.log.getCode())));
        param.put("from", List.of(DateTimeUtils.convertToShortTimestamp(this.from).toString()));
        param.put("to", List.of(DateTimeUtils.convertToShortTimestamp(this.to).toString()));
        param.put("limit", List.of(this.limit.toString()));
        return param;
    }
}