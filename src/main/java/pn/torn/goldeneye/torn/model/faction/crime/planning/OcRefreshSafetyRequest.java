package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 联合刷新安全边界求解请求。
 *
 * @param members 当前成员可用时间线
 * @param baseDemands 当前计划内OC的既有需求
 * @param baseChains 当前计划内高阶根及其完整后继义务
 * @param normalTemplates 计划内普通池随机结果模板
 * @param highChains 计划内高阶池完整链模板
 * @param planningTime 规划基准时间
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
public record OcRefreshSafetyRequest(List<OcMemberCandidate> members,
                                     List<OcTeamDemand> baseDemands,
                                     List<List<OcTeamDemand>> baseChains,
                                     List<OcTeamDemand> normalTemplates,
                                     List<List<OcTeamDemand>> highChains,
                                     LocalDateTime planningTime) {
    public OcRefreshSafetyRequest {
        members = members == null ? List.of() : List.copyOf(members);
        baseDemands = baseDemands == null ? List.of() : List.copyOf(baseDemands);
        baseChains = baseChains == null ? List.of() : baseChains.stream().map(List::copyOf).toList();
        normalTemplates = normalTemplates == null ? List.of() : List.copyOf(normalTemplates);
        highChains = highChains == null ? List.of() : highChains.stream().map(List::copyOf).toList();
    }

    /**
     * 创建不包含当前高阶链义务的求解请求。
     *
     * @param members 当前成员可用时间线
     * @param baseDemands 当前计划内OC的既有需求
     * @param normalTemplates 计划内普通池随机结果模板
     * @param highChains 计划内高阶池完整链模板
     * @param planningTime 规划基准时间
     */
    public OcRefreshSafetyRequest(List<OcMemberCandidate> members,
                                  List<OcTeamDemand> baseDemands,
                                  List<OcTeamDemand> normalTemplates,
                                  List<List<OcTeamDemand>> highChains,
                                  LocalDateTime planningTime) {
        this(members, baseDemands, List.of(), normalTemplates, highChains, planningTime);
    }
}
