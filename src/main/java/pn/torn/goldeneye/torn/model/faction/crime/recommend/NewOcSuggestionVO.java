package pn.torn.goldeneye.torn.model.faction.crime.recommend;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 新建OC队伍建议VO
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.03
 */
@Data
@AllArgsConstructor
public class NewOcSuggestionVO {
    /**
     * 建议新建队伍数量
     */
    private Integer suggestedNewOcCount;
    /**
     * 推荐的OC类型列表
     */
    private List<RecommendedOcType> recommendedOcTypes;
    /**
     * 分析详情
     */
    private OcAnalysisDetail analysisDetail;
    /**
     * 建议说明
     */
    private String suggestion;
}