package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcLiquidityAnchor;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberInterval;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPauseAssessment;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyRequest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 有限事件时间线推进过程中的可变成员占用状态。
 *
 * <p>同一成员的实际占用区间不得重叠：通过按占用推进availableAt并在每次占用后追加区间实现。
 * 占用区间同时用于最终一致性校验。纯内存对象，不访问数据库、HTTP或Redis。</p>
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
final class OcTimelineState {
    /**
     * 占用无法证明释放的成员可用时间哨兵：窗口内永远不可用。
     */
    static final LocalDateTime NEVER = LocalDateTime.MAX;

    private final LocalDateTime snapshotTime;
    private final Map<Long, LocalDateTime> availableAt = new HashMap<>();
    private final List<OcMemberInterval> intervals = new ArrayList<>();
    private final List<OcLiquidityAnchor> anchors = new ArrayList<>();
    private final List<OcPauseAssessment> pauses = new ArrayList<>();

    /**
     * 从求解请求构造初始状态。
     *
     * @param request 求解请求
     */
    OcTimelineState(OcRefreshSafetyRequest request) {
        this.snapshotTime = request.planningTime();
        for (OcMemberCandidate member : request.members()) {
            availableAt.put(member.userId(), request.isUnprovableMember(member.userId())
                    ? NEVER : member.availableAt());
        }
    }

    private OcTimelineState(LocalDateTime snapshotTime, Map<Long, LocalDateTime> availableAt,
                            List<OcMemberInterval> intervals, List<OcLiquidityAnchor> anchors,
                            List<OcPauseAssessment> pauses) {
        this.snapshotTime = snapshotTime;
        this.availableAt.putAll(availableAt);
        this.intervals.addAll(intervals);
        this.anchors.addAll(anchors);
        this.pauses.addAll(pauses);
    }

    /**
     * 深拷贝当前状态，供分支搜索使用。
     *
     * @return 可独立推进的状态副本
     */
    OcTimelineState copy() {
        return new OcTimelineState(snapshotTime, availableAt, intervals, anchors, pauses);
    }

    /**
     * 查询成员下一空闲时间。
     *
     * @param userId 成员用户ID
     * @return 当前占用结束时间；不可用时返回{@link #NEVER}
     */
    LocalDateTime availableAt(long userId) {
        return availableAt.getOrDefault(userId, NEVER);
    }

    /**
     * 判断成员在证明窗口内是否可参与规划。
     *
     * @param userId 成员用户ID
     * @return 可参与时返回true
     */
    boolean isUsable(long userId) {
        LocalDateTime time = availableAt(userId);
        return time != null && !NEVER.equals(time);
    }

    /**
     * 记录一次成员占用并在占用结束后释放成员。占用区间首尾相接不算重叠。
     *
     * @param userId 成员用户ID
     * @param from 占用开始时间
     * @param until 占用结束（释放）时间
     * @param source 占用来源
     */
    void occupy(long userId, LocalDateTime from, LocalDateTime until,
                OcMemberInterval.IntervalSource source) {
        intervals.add(new OcMemberInterval(userId, from, until, source));
        LocalDateTime current = availableAt.get(userId);
        if (current == null || NEVER.equals(current) || until.isAfter(current)) {
            availableAt.put(userId, until);
        }
    }

    /**
     * 追加一个已证明完成—释放锚点。
     *
     * @param anchor 流动性锚点
     */
    void addAnchor(OcLiquidityAnchor anchor) {
        anchors.add(anchor);
    }

    /**
     * 追加一条停转评估。
     *
     * @param pause 停转评估
     */
    void addPause(OcPauseAssessment pause) {
        pauses.add(pause);
    }

    /**
     * 获取全部已证明锚点，按释放时间排序。
     *
     * @return 流动性锚点链
     */
    List<OcLiquidityAnchor> anchors() {
        return anchors.stream()
                .sorted(Comparator.comparing(OcLiquidityAnchor::releaseAt)
                        .thenComparing(OcLiquidityAnchor::anchorKey))
                .toList();
    }

    /**
     * 获取全部停转评估。
     *
     * @return 停转评估列表
     */
    List<OcPauseAssessment> pauses() {
        return List.copyOf(pauses);
    }

    /**
     * 获取全部成员占用区间。
     *
     * @return 占用区间列表
     */
    List<OcMemberInterval> intervals() {
        return List.copyOf(intervals);
    }

    /**
     * 获取快照时间。
     *
     * @return 规划基准时间
     */
    LocalDateTime snapshotTime() {
        return snapshotTime;
    }

    /**
     * 校验同一成员不存在重叠占用区间。
     *
     * @return 全部区间互不重叠时返回true
     */
    boolean hasNoOverlappingIntervals() {
        Map<Long, List<OcMemberInterval>> byMember = new HashMap<>();
        intervals.forEach(interval -> byMember
                .computeIfAbsent(interval.userId(), ignored -> new ArrayList<>()).add(interval));
        for (List<OcMemberInterval> memberIntervals : byMember.values()) {
            List<OcMemberInterval> sorted = memberIntervals.stream()
                    .sorted(Comparator.comparing(OcMemberInterval::occupiedFrom)).toList();
            for (int index = 1; index < sorted.size(); index++) {
                if (sorted.get(index - 1).overlaps(sorted.get(index))) {
                    return false;
                }
            }
        }
        return true;
    }
}
