package pn.torn.goldeneye.torn.model.faction.crime.recommend;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * OC分析详情
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.03
 */
@Data
@Builder
public class OcAnalysisDetail {
    /**
     * 合格成员总数（能胜任至少一种OC的成员）
     */
    private Integer totalMembers;
    /**
     * 空闲的合格成员数
     */
    private Integer idleMembers;
    /**
     * 招募中OC占用成员数
     */
    private Integer recruitingOcMembers;
    /**
     * 计划中OC占用成员数
     */
    private Integer planningOcMembers;
    /**
     * 即将停转的OC数量（24小时内）
     */
    private Integer nearStopOcCount;
    /**
     * 即将停转的OC空闲岗位数
     */
    private Integer nearStopVacantSlots;
    /**
     * 即将完成的OC数量（Planning状态）
     */
    private Integer nearCompleteOcCount;
    /**
     * 即将完成的OC将释放的成员数
     */
    private Integer nearReleasedMembers;
    /**
     * 可用于新队的成员数
     */
    private Integer availableForNewOc;
    /**
     * 各OC类型的详细分析
     */
    private Map<String, OcTypeAnalysis> ocTypeDetails;
    /**
     * 检查时间
     */
    private LocalDateTime checkTime;

    public OcAnalysisDetail(int totalQualifiedMembers, int totalIdleMembers, int recruitingOcMembers,
                            int planningOcMembers, int nearStopOcCount, int nearStopVacantSlots,
                            int nearCompleteOcCount, LocalDateTime checkTime,
                            List<OcTypeAnalysis> ocTypeAnalyses) {
        this.totalMembers = totalQualifiedMembers;
        this.idleMembers = totalIdleMembers;
        this.recruitingOcMembers = recruitingOcMembers;
        this.planningOcMembers = planningOcMembers;
        this.nearStopOcCount = nearStopOcCount;
        this.nearStopVacantSlots = nearStopVacantSlots;
        this.nearCompleteOcCount = nearCompleteOcCount;
        this.nearReleasedMembers = planningOcMembers;
        this.availableForNewOc = totalIdleMembers + planningOcMembers - nearStopVacantSlots;
        this.checkTime = checkTime;
        this.ocTypeDetails = ocTypeAnalyses.stream().collect(Collectors.toMap(
                analysis -> analysis.getOcName() + "-" + analysis.getRank(),
                Function.identity()
        ));
    }
}