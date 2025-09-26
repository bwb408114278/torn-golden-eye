package pn.torn.goldeneye.torn.model.torn.stats;

import lombok.Data;

import java.util.Map;

/**
 * Torn状态响应参数
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.26
 */
@Data
public class TornStatsVO {
    /**
     * 状态详情, Key为状态Key
     */
    private Map<String, Long> stats;
}