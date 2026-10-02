package pn.torn.goldeneye.torn.service.faction.oc.delay;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.torn.TornItemsDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.torn.TornItemsManager;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberDTO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberListVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberVO;
import pn.torn.goldeneye.torn.model.faction.oc.delay.OcDelayCauseEntry;
import pn.torn.goldeneye.torn.model.faction.oc.delay.OcDelayReasonEnum;
import pn.torn.goldeneye.torn.model.user.TornUserStatusVO;
import pn.torn.goldeneye.torn.service.faction.oc.OcPreparationTimeCalculator;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * OC延误归因服务。
 * <p>
 * 承担完成检测循环中的成员状态采样与阻塞段结算，以及完成通知里的延误原因行渲染：
 * 采样按帮派每5分钟节流，只处理计划执行分钟已过去1分钟的pending OC；成员状态与道具名的取数
 * 入口同时供「即将结束」预告复用，避免两处口径漂移。延误的领域规则见 {@link OcDelayReasonResolver}
 * 与 {@link OcDelayCauseRecorder}，通知调度与消息装配仍由完成通知服务负责。
 *
 * @author Bai
 * @version 1.6.7
 * @since 2026.10.02
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OcDelayCauseService {
    private final TornApi tornApi;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO ocSlotDao;
    private final TornItemsManager itemsManager;
    private final OcDelayReasonResolver delayReasonResolver;
    private final OcDelayCauseRecorder delayCauseRecorder;
    // 帮派ID → 上次成员状态采样时间；重启后为空调度，最坏多采一次
    private final Map<Long, LocalDateTime> delaySampleTimeMap = new ConcurrentHashMap<>();
    // 成员状态采样间隔：延误时长以分钟计，5分钟粒度已足够识别主责，同时显著降低成员接口调用量
    private static final int DELAY_SAMPLE_INTERVAL_MINUTES = 5;
    // 计划执行分钟后需再等待的分钟数，用于确认Torn本轮的执行窗口已经错过
    private static final int DELAY_CONFIRM_MINUTES = 1;
    // 延误原因行前缀，只在单个OC的第一条成员原因行出现
    private static final String DELAY_REASON_PREFIX = "原因：";
    // 没有任何阻塞条目时的原因行
    private static final String DELAY_REASON_UNKNOWN_LINE = DELAY_REASON_PREFIX + "未知";

    /**
     * 采样pending OC的成员阻塞状态，并合并进OC的延误归因编码。
     * <p>
     * 同一帮派每{@value #DELAY_SAMPLE_INTERVAL_MINUTES}分钟最多采样一次；只采样计划执行分钟
     * 已经过去至少{@value #DELAY_CONFIRM_MINUTES}分钟的pending OC，避免把Torn自身的执行排期
     * 误判成阻塞。每个帮派本轮只调用一次成员接口、只查一次岗位快照；采样异常只记录告警，
     * 不影响完成检测与完成通知链路。
     *
     * @param faction    帮派配置
     * @param pendingOcs 本轮仍未完成的OC列表
     */
    public void sampleDelayCause(TornSettingFactionDO faction, List<TornFactionOcDO> pendingOcs) {
        try {
            LocalDateTime sampleTime = LocalDateTime.now();
            List<TornFactionOcDO> sampleableOcs = filterSampleableOcs(pendingOcs, sampleTime);
            if (sampleableOcs.isEmpty() || !isDelaySampleAllowed(faction.getId(), sampleTime)) {
                return;
            }

            List<TornFactionOcSlotDO> slotList = ocSlotDao.queryListByOc(sampleableOcs);
            Set<Long> sampleUserIdSet = slotList.stream()
                    .map(TornFactionOcSlotDO::getUserId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            if (sampleUserIdSet.isEmpty()) {
                return;
            }

            // 先记录采样时刻再调接口，接口异常时也按已采样节流，避免失败自旋放大调用量
            delaySampleTimeMap.put(faction.getId(), sampleTime);
            Optional<Map<Long, TornUserStatusVO>> statusMap =
                    queryMemberStatusMap(faction.getId(), sampleUserIdSet);
            if (statusMap.isEmpty()) {
                // 没拿到可用成员状态时若按“全员无阻塞”合并，会把正在阻塞的成员在本次采样时刻错误闭合
                log.warn("OC延误归因未取到可用成员状态，放弃本轮采样, factionId={}, userIdCount={}",
                        faction.getId(), sampleUserIdSet.size());
                return;
            }

            updateDelayCause(sampleableOcs, slotList, statusMap.get(), sampleTime);
        } catch (Exception e) {
            log.warn("OC延误归因采样异常，跳过本轮采样, factionId={}", faction.getId(), e);
        }
    }

    // 逐OC合并本轮采样结果，岗位按OC分组避免成员跨OC错配
    private void updateDelayCause(List<TornFactionOcDO> ocList, List<TornFactionOcSlotDO> slotList,
                                  Map<Long, TornUserStatusVO> statusMap, LocalDateTime sampleTime) {
        Map<Long, List<TornFactionOcSlotDO>> slotMapByOc = slotList.stream()
                .filter(slot -> slot.getOcId() != null)
                .collect(Collectors.groupingBy(TornFactionOcSlotDO::getOcId));
        for (TornFactionOcDO oc : ocList) {
            Map<Long, OcDelayReasonResolver.OcDelayReason> samples = buildDelaySamples(
                    slotMapByOc.getOrDefault(oc.getId(), List.of()), statusMap);
            saveDelayCause(oc, delayCauseRecorder.merge(oc.getDelayCause(), samples, sampleTime));
        }
    }

    // 仅在归因编码变化时写库，并同步内存DO供同批次渲染使用
    private void saveDelayCause(TornFactionOcDO oc, String delayCause) {
        if (Objects.equals(delayCause, oc.getDelayCause())) {
            return;
        }

        ocDao.lambdaUpdate()
                .set(TornFactionOcDO::getDelayCause, delayCause)
                .eq(TornFactionOcDO::getId, oc.getId())
                .update();
        oc.setDelayCause(delayCause);
    }

    // 判定该OC全部槽位成员本轮是否阻塞；未命中的成员不出现在结果中，视为本轮未阻塞
    private Map<Long, OcDelayReasonResolver.OcDelayReason> buildDelaySamples(
            List<TornFactionOcSlotDO> slots, Map<Long, TornUserStatusVO> statusMap) {
        Map<Long, OcDelayReasonResolver.OcDelayReason> samples = new HashMap<>();
        for (TornFactionOcSlotDO slot : slots) {
            if (slot.getUserId() == null) {
                continue;
            }

            delayReasonResolver.resolve(statusMap.get(slot.getUserId()),
                            slot.getRequiredItemId(), slot.getRequiredItemAvailable())
                    .ifPresent(reason -> samples.put(slot.getUserId(), reason));
        }

        return samples;
    }

    // 只采样计划执行分钟已过去至少DELAY_CONFIRM_MINUTES分钟的OC，避免把Torn自身排期误判成阻塞
    private List<TornFactionOcDO> filterSampleableOcs(List<TornFactionOcDO> pendingOcs,
                                                      LocalDateTime sampleTime) {
        return pendingOcs.stream()
                .filter(oc -> oc.getReadyTime() != null)
                .filter(oc -> !OcPreparationTimeCalculator.calculatePlannedTime(oc.getReadyTime())
                        .plusMinutes(DELAY_CONFIRM_MINUTES).isAfter(sampleTime))
                .toList();
    }

    // 同一帮派每DELAY_SAMPLE_INTERVAL_MINUTES分钟最多采样一次
    private boolean isDelaySampleAllowed(long factionId, LocalDateTime sampleTime) {
        LocalDateTime lastSampleTime = delaySampleTimeMap.get(factionId);
        return lastSampleTime == null
                || !sampleTime.isBefore(lastSampleTime.plusMinutes(DELAY_SAMPLE_INTERVAL_MINUTES));
    }

    /**
     * 查询帮派成员中目标成员的当前状态；一次帮派成员接口覆盖全部目标成员。
     *
     * @param factionId 帮派ID
     * @param userIdSet 目标成员ID集合
     * @return 目标成员状态；接口无数据或一个目标成员都没命中时返回空
     */
    public Optional<Map<Long, TornUserStatusVO>> queryMemberStatusMap(long factionId, Set<Long> userIdSet) {
        TornFactionMemberListVO resp = tornApi.sendRequest(
                new TornFactionMemberDTO(factionId), TornFactionMemberListVO.class);
        if (resp == null || CollectionUtils.isEmpty(resp.getMembers())) {
            return Optional.empty();
        }

        Map<Long, TornUserStatusVO> statusMap = new HashMap<>();
        for (TornFactionMemberVO member : resp.getMembers()) {
            if (userIdSet.contains(member.getId()) && member.getStatus() != null) {
                statusMap.put(member.getId(), member.getStatus());
            }
        }
        // 一个目标成员都没命中说明这批成员数据不可信；按“全员正常”处理会把正在发生的阻塞段错误闭合
        return statusMap.isEmpty() ? Optional.empty() : Optional.of(statusMap);
    }

    /**
     * 用实际执行时间封闭全部未闭合阻塞段并写库，供同批次完成通知渲染。
     * <p>
     * 结算异常只记录告警，完成通知继续使用库中已有编码。
     *
     * @param completedOcs 本轮已完成的OC列表
     */
    public void settleDelayCause(List<TornFactionOcDO> completedOcs) {
        try {
            for (TornFactionOcDO oc : completedOcs) {
                if (oc.getExecutedTime() == null) {
                    continue;
                }

                saveDelayCause(oc, delayCauseRecorder.settle(oc.getDelayCause(), oc.getExecutedTime()));
            }
        } catch (Exception e) {
            log.warn("OC延误归因结算异常，完成通知将使用已有编码", e);
        }
    }

    /**
     * 构建单个OC的延误原因行，每个阻塞过的成员一行、按净阻塞累计降序。
     *
     * @param oc      延误OC
     * @param userMap 成员ID → 用户映射
     * @return 原因行；没有任何阻塞条目时返回单行「原因：未知」
     */
    public List<String> buildDelayReasonLines(TornFactionOcDO oc, Map<Long, TornUserDO> userMap) {
        List<OcDelayCauseEntry> entries = delayCauseRecorder.decode(oc.getDelayCause());
        if (entries.isEmpty()) {
            return List.of(DELAY_REASON_UNKNOWN_LINE);
        }

        long executedMinute = OcDelayCauseRecorder.toMinuteBucket(oc.getExecutedTime());
        List<OcDelayCauseEntry> sortedEntries = entries.stream()
                .sorted(Comparator.comparingLong((OcDelayCauseEntry entry) -> entry.totalMinutes(executedMinute))
                        .reversed()
                        .thenComparingLong(OcDelayCauseEntry::userId))
                .toList();
        int maxDelayMinutes = sortedEntries.getFirst().totalMinutes(executedMinute);
        List<String> reasonLines = sortedEntries.stream()
                .map(entry -> buildReasonText(entry, userMap.get(entry.userId()), executedMinute, maxDelayMinutes))
                .collect(Collectors.toCollection(ArrayList::new));
        reasonLines.set(0, DELAY_REASON_PREFIX + reasonLines.getFirst());
        return reasonLines;
    }

    /**
     * 拼装单个成员的原因文案。
     *
     * @param entry           归因条目
     * @param user            成员信息；查不到时为null
     * @param executedMinute  完成时刻的分钟桶
     * @param maxDelayMinutes 本OC中最大的净阻塞累计分钟，用于标记最终阻塞
     * @return 形如「昵称[ID] 缺道具(道具名，延误约N分钟，最终阻塞)」的一行文案
     */
    private String buildReasonText(OcDelayCauseEntry entry, TornUserDO user, long executedMinute,
                                   int maxDelayMinutes) {
        int totalMinutes = entry.totalMinutes(executedMinute);
        List<String> parts = new ArrayList<>();
        if (entry.itemId() != null) {
            parts.add(buildItemReasonText(entry.reason(), entry.itemId()));
        }
        parts.add("延误约" + totalMinutes + "分钟");
        if (totalMinutes == maxDelayMinutes) {
            parts.add("最终阻塞");
        }

        String name = user != null ? user.getNickname() : String.valueOf(entry.userId());
        return name + "[" + entry.userId() + "] " + entry.reason().getLabel()
                + "(" + String.join("，", parts) + ")";
    }

    // 主原因为缺道具时道具名直接写出，其余原因的道具是补充信息，加「另缺」前缀
    private String buildItemReasonText(OcDelayReasonEnum reason, int itemId) {
        String itemName = buildItemName(itemId);
        return reason == OcDelayReasonEnum.ITEM ? itemName : "另缺 " + itemName;
    }

    /**
     * 查询道具展示名。
     * <p>
     * 「即将结束」预告的道具缺失提醒与延误原因行共用本方法，避免两处道具名口径漂移。
     *
     * @param itemId 道具ID
     * @return 道具展示名；物品表中查不到时降级为#ID
     */
    public String buildItemName(int itemId) {
        Map<Integer, TornItemsDO> itemMap = itemsManager.getMap();
        return itemMap.containsKey(itemId) ? itemMap.get(itemId).getItemName() : "#" + itemId;
    }
}
