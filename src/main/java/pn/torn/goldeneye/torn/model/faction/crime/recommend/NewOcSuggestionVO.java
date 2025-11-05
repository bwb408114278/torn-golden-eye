package pn.torn.goldeneye.torn.model.faction.crime.recommend;

import lombok.AllArgsConstructor;
import lombok.Data;

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
     * 分析详情
     */
    private OcAnalysisDetail analysisDetail;
    /**
     * 建议说明
     */
    private String suggestion;
}