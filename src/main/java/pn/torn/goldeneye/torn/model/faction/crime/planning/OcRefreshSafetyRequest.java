package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 有限事件时间线求解请求。
 *
 * @param members              当前帮派候选成员
 * @param unprovableMemberIds  占用无法证明释放的成员ID集合，窗口内不可参与复用
 * @param obligations          快照事实义务：已有人OC、已启动链后继、计划内无人OC
 * @param chainSuccessorsByKey 按义务键索引的已启动链剩余后继模板
 * @param normalTemplates      计划内普通池随机结果模板
 * @param highChains           计划内高阶池完整链模板
 * @param planningTime         规划基准时间（快照时间）
 * @author Bai
 * @version 1.3.0
 * @since 2026.07.17
 */
public record OcRefreshSafetyRequest(
        List<OcMemberCandidate> members,
        Set<Long> unprovableMemberIds,
        List<OcTimelineObligation> obligations,
        Map<String, List<OcTeamDemand>> chainSuccessorsByKey,
        List<OcTeamDemand> normalTemplates,
        List<List<OcTeamDemand>> highChains,
        LocalDateTime planningTime) {
    public OcRefreshSafetyRequest {
        members = members == null ? List.of() : List.copyOf(members);
        unprovableMemberIds = unprovableMemberIds == null ? Set.of() : Set.copyOf(unprovableMemberIds);
        obligations = obligations == null ? List.of() : List.copyOf(obligations);
        chainSuccessorsByKey = chainSuccessorsByKey == null
                ? Map.of() : Map.copyOf(chainSuccessorsByKey);
        normalTemplates = normalTemplates == null ? List.of() : List.copyOf(normalTemplates);
        highChains = highChains == null ? List.of() : highChains.stream().map(List::copyOf).toList();
    }

    /**
     * 判断成员在证明窗口内是否不可参与规划。
     *
     * @param userId 成员用户ID
     * @return 占用无法证明释放时返回true
     */
    public boolean isUnprovableMember(long userId) {
        return unprovableMemberIds.contains(userId);
    }
}
