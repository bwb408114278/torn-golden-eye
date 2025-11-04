package pn.torn.goldeneye.torn.model.faction.crime.recommend;

import lombok.Data;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcDO;

/**
 * OC类型分析结果
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.03
 */
@Data
public class OcTypeAnalysis {
    /**
     * OC名称
     */
    private String ocName;
    /**
     * OC登记
     */
    private Integer rank;
    /**
     * 需要人员
     */
    private Integer requiredMembers;
    /**
     * 期望收益
     */
    private Long expectedReward;
    /**
     * 合格成员
     */
    private Integer qualifiedMembers;
    /**
     * 空闲成员
     */
    private Integer idleQualifiedMembers;
    /**
     * 快要释放的人
     */
    private Integer nearReleaseMembers;
    /**
     * 可用人员数量
     */
    private Integer availableMembers;
    /**
     * 建议新OC数量
     */
    private Integer suggestedNewOcCount;

    public OcTypeAnalysis(TornSettingOcDO setting, int qualifiedMembers, int idleQualifiedMembers,
                          int nearReleaseMembers, int availableMembers) {
        this.ocName = setting.getOcName();
        this.rank = setting.getRank();
        this.requiredMembers = setting.getRequiredMembers();
        this.expectedReward = setting.getExpectedReward();
        this.qualifiedMembers = qualifiedMembers;
        this.idleQualifiedMembers = idleQualifiedMembers;
        this.nearReleaseMembers = nearReleaseMembers;
        this.availableMembers = availableMembers;
        this.suggestedNewOcCount = 0;
    }
}