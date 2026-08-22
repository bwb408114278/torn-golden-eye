package pn.torn.goldeneye.torn.model.torn.stats;

import lombok.Data;
import pn.torn.goldeneye.base.torn.TornReqParam;

/**
 * Torn状态请求
 *
 * @author Bai
 * @version 1.4.0
 * @since 2025.09.26
 */
@Data
public class TornStatsDTO implements TornReqParam {
    @Override
    public String uri() {
        return "/torn";
    }

    @Override
    public Long getId() {
        return null;
    }

    @Override
    public String getSection() {
        return "stats";
    }
}
