package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.springframework.stereotype.Service;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcChainDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcPlanProfileDO;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcChainTemplateResult;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanSlot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamDemand;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 根据配置构造可参与刷新安全计算的完整高阶链模板。
 *
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
@Service
public class OcChainPlanningService {
    private static final String HIGH_CHAIN_ROOT = "HIGH_CHAIN_ROOT";
    private static final String READY = "READY";

    /**
     * 根据快照中的有效配置构造完整高阶链。
     *
     * @param snapshot 同一规划周期内的不可变快照
     * @return 通过状态、范围和完整性校验的高阶链节点列表
     */
    public List<List<OcTeamDemand>> buildReadyChains(OcPlanningSnapshot snapshot) {
        return buildReadyChainResult(snapshot).chains();
    }

    /**
     * 构造完整高阶链，并返回被阻断的配置警告。
     *
     * @param snapshot 同一规划周期内的不可变快照
     * @return 高阶链模板及配置警告
     */
    public OcChainTemplateResult buildReadyChainResult(OcPlanningSnapshot snapshot) {
        Map<String, List<TornSettingOcChainDO>> byCode = new LinkedHashMap<>();
        snapshot.chains().forEach(edge -> byCode.computeIfAbsent(edge.getChainCode(),
                ignored -> new ArrayList<>()).add(edge));
        List<String> warnings = new ArrayList<>();
        validatePlannedRootsHaveChain(snapshot, byCode, warnings);
        List<ChainCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, List<TornSettingOcChainDO>> entry : byCode.entrySet()) {
            List<TornSettingOcChainDO> edges = new ArrayList<>(entry.getValue());
            edges.sort(Comparator.comparingInt(TornSettingOcChainDO::getSequenceNo));
            if (!edges.isEmpty() && isPlannedRoot(snapshot, edges.getFirst())) {
                collectCandidate(snapshot, entry.getKey(), edges, candidates, warnings);
            }
        }
        Set<String> conflictedRoots = sharedRoots(candidates, warnings);
        List<List<OcTeamDemand>> chains = candidates.stream()
                .filter(candidate -> !conflictedRoots.contains(candidate.keys().getFirst()))
                .map(candidate -> buildDemands(snapshot, candidate.keys()))
                .toList();
        return new OcChainTemplateResult(chains, warnings);
    }

    /**
     * 校验计划内链配置并收集有效候选链。
     *
     * @param snapshot 规划快照
     * @param chainCode 链编码
     * @param edges 已排序的链边
     * @param candidates 有效候选链集合
     * @param warnings 配置警告集合
     */
    private void collectCandidate(OcPlanningSnapshot snapshot, String chainCode,
                                  List<TornSettingOcChainDO> edges,
                                  List<ChainCandidate> candidates,
                                  List<String> warnings) {
        List<String> keys = validateAndBuildKeys(chainCode, edges, warnings);
        if (!keys.isEmpty() && isReadyChain(snapshot, keys)) {
            candidates.add(new ChainCandidate(chainCode, keys));
        } else if (!keys.isEmpty()) {
            warnings.add("高阶链节点配置无效，已阻断链: " + chainCode);
        }
    }

    /**
     * 校验链序号、父子连续性和重复节点，并生成节点键序列。
     *
     * @param chainCode 链编码
     * @param edges 已排序的链边
     * @param warnings 配置警告集合
     * @return 合法链节点键；配置非法时返回空集合
     */
    private List<String> validateAndBuildKeys(String chainCode,
                                              List<TornSettingOcChainDO> edges,
                                              List<String> warnings) {
        if (edges.isEmpty()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        Set<String> nodes = new HashSet<>();
        TornSettingOcChainDO first = edges.getFirst();
        String rootKey = key(first.getParentRank(), first.getParentOcName());
        keys.add(rootKey);
        nodes.add(rootKey);
        TornSettingOcChainDO previous = null;
        for (int index = 0; index < edges.size(); index++) {
            TornSettingOcChainDO edge = edges.get(index);
            if (edge.getSequenceNo() != index + 1
                    || previous != null && !sameNode(previous.getChildRank(),
                    previous.getChildOcName(), edge.getParentRank(), edge.getParentOcName())) {
                warnings.add("高阶链配置不连续，已阻断链: " + chainCode);
                return List.of();
            }
            String childKey = key(edge.getChildRank(), edge.getChildOcName());
            if (!nodes.add(childKey)) {
                warnings.add("高阶链包含重复节点，已阻断链: " + chainCode);
                return List.of();
            }
            keys.add(childKey);
            previous = edge;
        }
        return keys;
    }

    /**
     * 识别多个链编码共享同一计划内根节点的配置冲突。
     *
     * @param candidates 候选链集合
     * @param warnings 配置警告集合
     * @return 存在冲突的根节点键集合
     */
    private Set<String> sharedRoots(List<ChainCandidate> candidates, List<String> warnings) {
        Map<String, List<String>> codesByRoot = new HashMap<>();
        candidates.forEach(candidate -> codesByRoot.computeIfAbsent(candidate.keys().getFirst(),
                ignored -> new ArrayList<>()).add(candidate.chainCode()));
        Set<String> conflicted = new HashSet<>();
        codesByRoot.forEach((root, codes) -> {
            if (codes.size() > 1) {
                conflicted.add(root);
                warnings.add("同一高阶根配置了多条链，已阻断自动规划: "
                        + root + " -> " + String.join(",", codes));
            }
        });
        return conflicted;
    }

    /**
     * 将链节点键转换为匿名队伍需求模板。
     *
     * @param snapshot 规划快照
     * @param keys 链节点键序列
     * @return 高阶链需求模板
     */
    private List<OcTeamDemand> buildDemands(OcPlanningSnapshot snapshot, List<String> keys) {
        return keys.stream().map(key -> {
            TornSettingOcPlanProfileDO profile = snapshot.profiles().get(key);
            List<OcPlanSlot> slots = snapshot.slotTemplates().get(key);
            return new OcTeamDemand(0L, profile.getOcName(), profile.getRank(),
                    null, null, true, slots, Set.of(), Set.of());
        }).toList();
    }

    /**
     * 校验链内全部节点档案、状态和岗位模板是否可用于自动规划。
     *
     * @param snapshot 规划快照
     * @param keys 链节点键序列
     * @return 全部节点有效时返回true
     */
    private boolean isReadyChain(OcPlanningSnapshot snapshot, List<String> keys) {
        if (keys.stream().anyMatch(snapshot.invalidOcKeys()::contains)) {
            return false;
        }
        return keys.stream().allMatch(key -> {
            TornSettingOcPlanProfileDO profile = snapshot.profiles().get(key);
            return profile != null && READY.equals(profile.getPlanStatus())
                    && !snapshot.slotTemplates().getOrDefault(key, List.of()).isEmpty();
        });
    }

    /**
     * 校验所有计划内高阶根是否配置了链定义。
     *
     * @param snapshot 规划快照
     * @param byCode 按链编码分组的链边
     * @param warnings 配置警告集合
     */
    private void validatePlannedRootsHaveChain(
            OcPlanningSnapshot snapshot,
            Map<String, List<TornSettingOcChainDO>> byCode,
            List<String> warnings) {
        Set<String> configuredRoots = new HashSet<>();
        byCode.values().stream().filter(edges -> !edges.isEmpty())
                .map(List::getFirst)
                .map(edge -> key(edge.getParentRank(), edge.getParentOcName()))
                .forEach(configuredRoots::add);
        snapshot.profiles().forEach((rootKey, profile) -> {
            if (snapshot.policy().enabledOcKeys().contains(rootKey)
                    && READY.equals(profile.getPlanStatus())
                    && HIGH_CHAIN_ROOT.equals(profile.getSpawnPool())
                    && !configuredRoots.contains(rootKey)) {
                warnings.add("计划内高阶根缺少链配置，已阻断自动规划: " + rootKey);
            }
        });
    }

    /**
     * 判断链首节点是否属于当前帮派规划范围。
     *
     * @param snapshot 规划快照
     * @param first 第一条链边
     * @return 属于规划范围时返回true
     */
    private boolean isPlannedRoot(OcPlanningSnapshot snapshot,
                                  TornSettingOcChainDO first) {
        return snapshot.policy().enabledOcKeys().contains(
                key(first.getParentRank(), first.getParentOcName()));
    }

    /**
     * 判断两个链节点是否为相同OC。
     *
     * @param leftRank 左节点等级
     * @param leftName 左节点名称
     * @param rightRank 右节点等级
     * @param rightName 右节点名称
     * @return 等级和名称均相同时返回true
     */
    private boolean sameNode(int leftRank, String leftName, int rightRank, String rightName) {
        return leftRank == rightRank && leftName.equals(rightName);
    }

    /**
     * 构造OC规划键。
     *
     * @param rank OC等级
     * @param name OC名称
     * @return OC规划键
     */
    private String key(int rank, String name) {
        return OcPlanningSnapshot.ocKey(rank, name);
    }

    /**
     * 已通过结构和节点配置校验的候选链。
     *
     * @param chainCode 链编码
     * @param keys 链节点键序列
     */
    private record ChainCandidate(String chainCode, List<String> keys) {
    }
}
