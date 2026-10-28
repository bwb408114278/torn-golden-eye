package pn.torn.goldeneye.torn.service.faction.oc.recommend.metric.flow;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcChainDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcChainDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.torn.manager.faction.crime.recommend.TornOcRecommendManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcFlowManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcSlotManager;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.flow.OcFlowGraph;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.flow.OcFlowKey;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.flow.OcFlowValuation;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 计算成员相对逐坑标尺替补的DAG期望收益增量。
 *
 * @author Bai
 * @version 1.9.5
 * @since 2026.10.27
 */
@Service
@RequiredArgsConstructor
public class OcFlowMarginService {
    private static final double PERCENT = 100.0;

    private final TornSettingOcFlowManager flowManager;
    private final TornSettingOcSlotManager slotManager;
    private final TornSettingOcChainDAO chainDao;
    private final TornOcRecommendManager ocRecommendManager;
    private final OcFlowValuator valuator;

    /**
     * 为候选写入DAG边际收益；无图、参与者缺失或结局/链值不全时保留null并回退R6。
     *
     * @param candidates         候选列表
     * @param factionId          帮派ID
     * @param ocList             本次候选及当前基线涉及的OC
     * @param allSlots           这些OC的全部岗位，包含有人的岗位
     * @param allocatedPassRates 本次分配已占岗位及其成员成功率
     * @return 写入边际后的候选，顺序不变
     */
    public List<OcRecommendCandidate> apply(List<OcRecommendCandidate> candidates, long factionId,
                                            List<TornFactionOcDO> ocList, List<TornFactionOcSlotDO> allSlots,
                                            Map<Long, Integer> allocatedPassRates) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        Map<OcFlowKey, Team> teams = buildTeams(factionId, ocList, allSlots, allocatedPassRates);
        Map<OcFlowKey, OcFlowKey> childByParent = loadChildByParent();
        Map<OcFlowKey, Optional<ResolvedTeam>> resolved = new HashMap<>();
        for (OcFlowKey key : teams.keySet()) {
            resolve(key, teams, childByParent, resolved, new HashSet<>());
        }

        List<OcRecommendCandidate> result = new ArrayList<>(candidates.size());
        for (OcRecommendCandidate candidate : candidates) {
            OcFlowKey key = OcFlowKey.of(candidate.oc().getName(), candidate.oc().getRank());
            BigDecimal marginalValue = resolved.getOrDefault(key, Optional.empty())
                    .map(team -> marginalOf(candidate, team)).orElse(null);
            result.add(candidate.withMarginalValue(marginalValue));
        }
        return result;
    }

    /**
     * 先装配全图标尺，再用当前真实成员和本次已分配成员覆盖对应坑位。
     */
    private Map<OcFlowKey, Team> buildTeams(long factionId, List<TornFactionOcDO> ocList,
                                            List<TornFactionOcSlotDO> allSlots,
                                            Map<Long, Integer> allocatedPassRates) {
        Map<OcFlowKey, List<TornSettingOcSlotDO>> settings = slotManager.getList().stream()
                .collect(Collectors.groupingBy(slot -> OcFlowKey.of(slot.getOcName(), slot.getRank())));
        Map<OcFlowKey, Team> teams = new HashMap<>();
        flowManager.getGraphs().forEach((key, graph) -> {
            List<TornSettingOcSlotDO> requirements = settings.getOrDefault(key, List.of());
            teams.put(key, new Team(graph, baseRates(requirements), requirementMap(requirements)));
        });

        Map<Long, List<TornFactionOcSlotDO>> slotsByOc = allSlots.stream()
                .collect(Collectors.groupingBy(TornFactionOcSlotDO::getOcId));
        for (TornFactionOcDO oc : ocList) {
            OcFlowKey key = OcFlowKey.of(oc.getName(), oc.getRank());
            Team original = teams.get(key);
            if (original == null) {
                continue;
            }
            Map<String, Double> rates = new HashMap<>(original.rates());
            Map<String, TornSettingOcSlotDO> requirements = new HashMap<>(original.requirements());
            for (TornFactionOcSlotDO slot : slotsByOc.getOrDefault(oc.getId(), List.of())) {
                TornSettingOcSlotDO requirement = ocRecommendManager.findSlotRequirement(factionId, oc, slot);
                if (requirement == null) {
                    requirement = requirements.get(OcFlowSlotCodes.normalize(slot.getPosition()));
                }
                if (requirement == null) {
                    continue;
                }
                String normalized = OcFlowSlotCodes.normalize(requirement.getSlotCode());
                requirements.put(normalized, requirement);
                Double substitute = OcFlowSubstitutePolicy.substituteRate(requirement);
                Integer actualRate = actualRate(slot, allocatedPassRates);
                rates.put(normalized, actualRate == null ? substitute : actualRate / PERCENT);
            }
            teams.put(key, new Team(original.graph(), rates, requirements));
        }
        return teams;
    }

    /**
     * 已分配岗位优先取本次成员，已有人岗位取其实测值，空位仍取标尺。
     */
    private Integer actualRate(TornFactionOcSlotDO slot, Map<Long, Integer> allocatedPassRates) {
        if (allocatedPassRates.containsKey(slot.getId())) {
            return allocatedPassRates.get(slot.getId());
        }
        return slot.getUserId() == null ? null : slot.getPassRate();
    }

    private Map<String, Double> baseRates(List<TornSettingOcSlotDO> requirements) {
        Map<String, Double> rates = new HashMap<>();
        for (TornSettingOcSlotDO requirement : requirements) {
            Double rate = OcFlowSubstitutePolicy.substituteRate(requirement);
            if (rate != null) {
                rates.put(OcFlowSlotCodes.normalize(requirement.getSlotCode()), rate);
            }
        }
        return rates;
    }

    private Map<String, TornSettingOcSlotDO> requirementMap(List<TornSettingOcSlotDO> requirements) {
        return requirements.stream().collect(Collectors.toMap(
                requirement -> OcFlowSlotCodes.normalize(requirement.getSlotCode()), requirement -> requirement));
    }

    private Map<OcFlowKey, OcFlowKey> loadChildByParent() {
        List<TornSettingOcChainDO> chains = chainDao.lambdaQuery()
                .eq(TornSettingOcChainDO::getEnabled, true)
                .eq(TornSettingOcChainDO::getDeleted, 0)
                .list();
        Map<OcFlowKey, OcFlowKey> result = new HashMap<>();
        for (TornSettingOcChainDO chain : chains) {
            result.put(OcFlowKey.of(chain.getParentOcName(), chain.getParentRank()),
                    OcFlowKey.of(chain.getChildOcName(), chain.getChildRank()));
        }
        return result;
    }

    /**
     * 独立OC优先使用分结局奖励；奖励为空的前置OC递归取后置链值。
     */
    private Optional<ResolvedTeam> resolve(OcFlowKey key, Map<OcFlowKey, Team> teams,
                                           Map<OcFlowKey, OcFlowKey> childByParent,
                                           Map<OcFlowKey, Optional<ResolvedTeam>> resolved,
                                           Set<OcFlowKey> visiting) {
        if (resolved.containsKey(key)) {
            return resolved.get(key);
        }
        Team team = teams.get(key);
        if (team == null || !visiting.add(key)) {
            return Optional.empty();
        }
        Optional<OcFlowValuation> direct = valuator.evaluate(team.graph(), team.rates(), null);
        Optional<ResolvedTeam> result;
        if (direct.isPresent()) {
            result = Optional.of(new ResolvedTeam(team, direct.get(), null));
        } else {
            OcFlowKey childKey = childByParent.get(key);
            Optional<ResolvedTeam> child = childKey == null ? Optional.empty()
                    : resolve(childKey, teams, childByParent, resolved, visiting);
            result = child.flatMap(value -> valuator.evaluate(team.graph(), team.rates(),
                            value.baseline().expectedValue())
                    .map(outcome -> new ResolvedTeam(team, outcome, value.baseline().expectedValue())));
        }
        visiting.remove(key);
        resolved.put(key, result);
        return result;
    }

    /**
     * 同一候选分别计算标尺替补与本人，避免把当前已有人岗位误当作基准。
     */
    private BigDecimal marginalOf(OcRecommendCandidate candidate, ResolvedTeam resolved) {
        Double substitute = OcFlowSubstitutePolicy.substituteRate(candidate.requirement());
        if (substitute == null || candidate.passRate() == null) {
            return null;
        }
        String slotCode = OcFlowSlotCodes.normalize(candidate.requirement().getSlotCode());
        Map<String, Double> baselineRates = new HashMap<>(resolved.team().rates());
        baselineRates.put(slotCode, substitute);
        Map<String, Double> candidateRates = new HashMap<>(baselineRates);
        candidateRates.put(slotCode, candidate.passRate() / PERCENT);

        Optional<OcFlowValuation> baseline = valuator.evaluate(
                resolved.team().graph(), baselineRates, resolved.chainValue());
        Optional<OcFlowValuation> current = valuator.evaluate(
                resolved.team().graph(), candidateRates, resolved.chainValue());
        if (baseline.isEmpty() || current.isEmpty()) {
            return null;
        }
        return current.get().expectedValue().subtract(baseline.get().expectedValue());
    }

    private record Team(OcFlowGraph graph, Map<String, Double> rates,
                        Map<String, TornSettingOcSlotDO> requirements) {
    }

    private record ResolvedTeam(Team team, OcFlowValuation baseline, BigDecimal chainValue) {
    }
}
