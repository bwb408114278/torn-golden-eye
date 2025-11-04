package pn.torn.goldeneye.torn.model.faction.crime.recommend;

import lombok.Builder;
import lombok.Data;

/**
 * 推荐的OC类型
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.03
 */
@Data
@Builder
public class RecommendedOcType {
    /**
     * OC名称
     */
    private String ocName;
    /**
     * OC级别
     */
    private Integer rank;
    /**
     * 需要人数
     */
    private Integer requiredMembers;
    /**
     * 建议开设数量
     */
    private Integer suggestedCount;
    /**
     * 推荐优先级（1最高）
     */
    private Integer priority;
    /**
     * 推荐理由
     */
    private String reason;
}