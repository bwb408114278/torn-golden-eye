package pn.torn.goldeneye.torn.manager.setting;

import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.cache.DataCacheManager;
import pn.torn.goldeneye.constants.torn.CacheConstants;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcFlowEdgeDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcFlowNodeDAO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcFlowEdgeDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcFlowNodeDO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.flow.*;

import java.util.*;

/**
 * OC流程图配置管理器。
 *
 * <p>两张表按OC名称与等级装配成流程图后整体缓存：装配与结构校验只在缓存失效后做一次，
 * 推荐链路每次请求只做一次Map查表。装配失败的图记录告警后跳过，调用方按「没有这张图」处理，
 * 不会因为个别OC配置写错而阻塞整个推荐。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.24
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TornSettingOcFlowManager implements DataCacheManager {
    private final TornSettingOcFlowNodeDAO flowNodeDao;
    private final TornSettingOcFlowEdgeDAO flowEdgeDao;
    @Lazy
    @Resource
    private TornSettingOcFlowManager flowManager;

    @Override
    public void warmUpCache() {
        flowManager.getGraphs();
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConstants.KEY_TORN_SETTING_OC_FLOW_NODE, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.KEY_TORN_SETTING_OC_FLOW_EDGE, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.KEY_TORN_SETTING_OC_FLOW_GRAPH, allEntries = true)})
    public void refreshCache() {
        log.info("OC流程图配置缓存已重置");
    }

    /**
     * 查询未删除的流程图节点配置。
     *
     * @return 按OC名称、等级、节点编码排序的节点配置
     */
    @Cacheable(value = CacheConstants.KEY_TORN_SETTING_OC_FLOW_NODE)
    public List<TornSettingOcFlowNodeDO> getNodes() {
        return flowNodeDao.lambdaQuery()
                .eq(TornSettingOcFlowNodeDO::getDeleted, 0)
                .orderByAsc(TornSettingOcFlowNodeDO::getOcName)
                .orderByAsc(TornSettingOcFlowNodeDO::getRank)
                .orderByAsc(TornSettingOcFlowNodeDO::getNodeCode)
                .list();
    }

    /**
     * 查询未删除的流程图边配置。
     *
     * @return 按OC名称、等级、起始节点、分支排序的边配置
     */
    @Cacheable(value = CacheConstants.KEY_TORN_SETTING_OC_FLOW_EDGE)
    public List<TornSettingOcFlowEdgeDO> getEdges() {
        return flowEdgeDao.lambdaQuery()
                .eq(TornSettingOcFlowEdgeDO::getDeleted, 0)
                .orderByAsc(TornSettingOcFlowEdgeDO::getOcName)
                .orderByAsc(TornSettingOcFlowEdgeDO::getRank)
                .orderByAsc(TornSettingOcFlowEdgeDO::getNodeCode)
                .orderByAsc(TornSettingOcFlowEdgeDO::getBranch)
                .list();
    }

    /**
     * 查询按OC名称与等级装配好的流程图。
     *
     * @return 流程图键到流程图的只读映射；装配失败的OC不在其中
     */
    @Cacheable(value = CacheConstants.KEY_TORN_SETTING_OC_FLOW_GRAPH)
    public Map<OcFlowKey, OcFlowGraph> getGraphs() {
        Map<OcFlowKey, List<TornSettingOcFlowNodeDO>> nodeRows = groupNodes(flowManager.getNodes());
        Map<OcFlowKey, List<TornSettingOcFlowEdgeDO>> edgeRows = groupEdges(flowManager.getEdges());
        Set<OcFlowKey> keys = new LinkedHashSet<>(nodeRows.keySet());
        keys.addAll(edgeRows.keySet());

        Map<OcFlowKey, OcFlowGraph> graphs = HashMap.newHashMap(keys.size());
        for (OcFlowKey key : keys) {
            OcFlowGraph graph = assemble(key, nodeRows.getOrDefault(key, List.of()),
                    edgeRows.getOrDefault(key, List.of()));
            if (graph != null) {
                graphs.put(key, graph);
            }
        }
        log.info("OC流程图装配完成, 配置键数={}, 成功装配={}", keys.size(), graphs.size());
        return Map.copyOf(graphs);
    }

    /**
     * 查询单张流程图。
     *
     * @param ocName OC名称
     * @param rank   OC等级
     * @return 流程图；未配置或装配失败时返回空
     */
    public Optional<OcFlowGraph> findGraph(String ocName, Integer rank) {
        if (ocName == null || rank == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(flowManager.getGraphs().get(OcFlowKey.of(ocName, rank)));
    }

    /**
     * 装配单张流程图，结构或字段不合法时告警并返回null。
     */
    private OcFlowGraph assemble(OcFlowKey key, List<TornSettingOcFlowNodeDO> nodeRows,
                                 List<TornSettingOcFlowEdgeDO> edgeRows) {
        try {
            List<OcFlowNode> nodes = new ArrayList<>(nodeRows.size());
            for (TornSettingOcFlowNodeDO row : nodeRows) {
                nodes.add(new OcFlowNode(row.getNodeCode(), OcFlowNodeTypeEnum.fromCode(row.getNodeType()),
                        splitParticipants(row.getParticipants())));
            }
            List<OcFlowEdge> edges = new ArrayList<>(edgeRows.size());
            for (TornSettingOcFlowEdgeDO row : edgeRows) {
                edges.add(new OcFlowEdge(row.getNodeCode(), OcFlowBranchEnum.fromCode(row.getBranch()),
                        row.getTargetNodeCode()));
            }
            return OcFlowGraph.of(key.ocName(), key.rank(), nodes, edges);
        } catch (IllegalArgumentException e) {
            log.warn("OC流程图装配跳过: ocName={}, rank={}, 原因={}", key.ocName(), key.rank(), e.getMessage());
            return null;
        }
    }

    /**
     * 按OC名称与等级归类节点配置，键缺失的行告警后跳过。
     */
    private Map<OcFlowKey, List<TornSettingOcFlowNodeDO>> groupNodes(List<TornSettingOcFlowNodeDO> rows) {
        Map<OcFlowKey, List<TornSettingOcFlowNodeDO>> grouped = new HashMap<>();
        for (TornSettingOcFlowNodeDO row : rows) {
            OcFlowKey key = keyOf(row.getOcName(), row.getRank(), row.getNodeCode());
            if (key != null) {
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
            }
        }
        return grouped;
    }

    /**
     * 按OC名称与等级归类边配置，键缺失的行告警后跳过。
     */
    private Map<OcFlowKey, List<TornSettingOcFlowEdgeDO>> groupEdges(List<TornSettingOcFlowEdgeDO> rows) {
        Map<OcFlowKey, List<TornSettingOcFlowEdgeDO>> grouped = new HashMap<>();
        for (TornSettingOcFlowEdgeDO row : rows) {
            OcFlowKey key = keyOf(row.getOcName(), row.getRank(), row.getNodeCode());
            if (key != null) {
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
            }
        }
        return grouped;
    }

    /**
     * 校验并构造流程图键，OC名称或等级缺失时告警返回null。
     */
    private OcFlowKey keyOf(String ocName, Integer rank, String nodeCode) {
        if (ocName == null || ocName.isBlank() || rank == null) {
            log.warn("OC流程图配置跳过: ocName={}, rank={}, nodeCode={}, 原因=键字段缺失",
                    ocName, rank, nodeCode);
            return null;
        }
        return OcFlowKey.of(ocName, rank);
    }

    /**
     * 拆分逗号分隔的参与者岗位编码，空串与空元素忽略。
     */
    private List<String> splitParticipants(String participants) {
        if (participants == null || participants.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : participants.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
