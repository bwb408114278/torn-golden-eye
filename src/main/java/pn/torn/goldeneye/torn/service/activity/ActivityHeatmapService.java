package pn.torn.goldeneye.torn.service.activity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.torn.model.activity.*;
import pn.torn.goldeneye.torn.model.activity.grid.ActivityGridLayout;
import pn.torn.goldeneye.torn.service.activity.query.ActivityDaySnapshot;
import pn.torn.goldeneye.torn.service.activity.query.ActivityHeatmapAggregator;
import pn.torn.goldeneye.torn.service.activity.query.ActivityHeatmapDataLoader;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 活跃度热力图查询服务
 * <p>
 * 保留个人、帮派、对比三个公开查询门面，参数为明确的{@link ActivityQueryRange}日期范围；
 * 数据加载委派{@link ActivityHeatmapDataLoader}（V3 归档 → V3 Redis → V2 Redis 逐日取一源），
 * 矩阵计算委派{@link ActivityHeatmapAggregator}，本类只负责标题、文案与 VO 组装。
 * 不调用 Torn API。
 *
 * @author Bai
 * @version 1.7.0
 * @since 2026.07.07
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityHeatmapService {

    /**
     * 范围内无任何有效 observed 槽时指令端返回的文本
     */
    public static final String NO_DATA_MESSAGE = "该时间范围暂无活跃度采样数据";

    private static final String HEATMAP_TITLE_SUFFIX = "] 活跃度热力图";
    private static final String LEGACY_NOTICE = "部分历史采样未区分 Idle，仅供趋势参考";
    private static final String TODAY_INCOMPLETE_NOTICE = "当日数据尚未完整";
    private static final String NOTICE_SEPARATOR = "；";
    private static final String SUBTITLE_SEPARATOR = "｜";
    private static final int SLOTS_PER_DAY = 96;
    private static final int MIN_SAMPLE_DAYS_NOTICE = 7;
    private static final int FULL_DOW_COUNT = 7;

    private final StringRedisTemplate redisTemplate;
    private final ActivityHeatmapDataLoader dataLoader;

    // ==================== 个人热力图 ====================

    /**
     * 查询个人活跃度热力图
     *
     * @param userId 用户 ID
     * @param range  查询日期范围
     * @return 个人热力图数据，范围内无有效采样时{@code hasData=false}
     */
    public PersonalActivityHeatmapVO queryPersonalHeatmap(long userId, ActivityQueryRange range) {
        validateQueryTarget(userId);
        ActivityGridLayout grid = range.grid();
        PersonalActivityHeatmapVO vo = PersonalActivityHeatmapVO.empty(buildPersonalTitle(userId), grid);
        vo.setTotalDays(range.totalDays());

        ActivityHeatmapAggregator.PersonalMatrix matrix = ActivityHeatmapAggregator.aggregatePersonal(
                dataLoader.loadUserDays(userId, range), grid);
        vo.setActiveRate(matrix.activeRate());
        vo.setObservedSamples(matrix.observedSamples());
        vo.setIdleRatio(matrix.idleRatio());
        vo.setSubtitle(buildCaliberSubtitlePrefix(range)
                + "有效采样覆盖率: " + formatPercent(calculateCoverage(matrix.totalObservedSlots(), range)));
        fillCommonMetadata(vo, matrix.totalObservedSlots(), matrix.actualDays(),
                matrix.observedRowCount(), matrix.legacyIncluded(), range, true);
        return vo;
    }

    // ==================== 帮派热力图 ====================

    /**
     * 查询帮派活跃度热力图（格内为平均有效活跃人数，颜色为人数锚点渐变 + Idle 占比暗化）
     *
     * @param factionId 帮派 ID
     * @param range     查询日期范围
     * @return 帮派热力图数据，范围内无有效采样时{@code hasData=false}
     */
    public FactionActivityHeatmapVO queryFactionHeatmap(long factionId, ActivityQueryRange range) {
        validateQueryTarget(factionId);
        ActivityGridLayout grid = range.grid();
        FactionActivityHeatmapVO vo = FactionActivityHeatmapVO.empty(buildFactionTitle(factionId), grid);
        vo.setTotalDays(range.totalDays());

        ActivityHeatmapAggregator.FactionMatrix matrix = ActivityHeatmapAggregator.aggregateFaction(
                dataLoader.loadFactionDays(factionId, range), grid);
        vo.setAverageOnlineCount(matrix.averageActiveCount());
        vo.setObservedSamples(matrix.observedSamples());
        vo.setIdleRatio(matrix.idleRatio());
        vo.setSubtitle(buildCaliberSubtitlePrefix(range)
                + "格内：平均有效活跃人数｜颜色：有效活跃人数渐变，Idle 越多越暗｜有效采样覆盖率: "
                + formatPercent(calculateCoverage(matrix.totalObservedSlots(), range)));
        fillCommonMetadata(vo, matrix.totalObservedSlots(), matrix.actualDays(),
                matrix.observedRowCount(), matrix.legacyIncluded(), range, true);
        return vo;
    }

    // ==================== 帮派对比 ====================

    /**
     * 对比两个帮派的活跃度
     * <p>
     * 同一个格子只有在双方都有有效观测（共同原始槽）时才参与对比；Idle 不参与比较和色差。
     *
     * @param faction1Id 帮派A ID
     * @param faction2Id 帮派B ID
     * @param range      查询日期范围
     * @return 对比热力图数据，范围内无共同有效采样时{@code hasData=false}
     */
    public ActivityComparisonHeatmapVO compareFactions(long faction1Id, long faction2Id, ActivityQueryRange range) {
        validateQueryTarget(faction1Id);
        validateQueryTarget(faction2Id);
        String display1 = buildDisplayName(faction1Id);
        String display2 = buildDisplayName(faction2Id);
        ActivityComparisonHeatmapVO vo = ActivityComparisonHeatmapVO.empty(
                faction1Id, display1, faction2Id, display2);
        vo.setTotalDays(range.totalDays());

        List<ActivityDaySnapshot.FactionDay> faction1Days = dataLoader.loadFactionDays(faction1Id, range);
        List<ActivityDaySnapshot.FactionDay> faction2Days = dataLoader.loadFactionDays(faction2Id, range);
        ActivityHeatmapAggregator.ComparisonMatrix matrix =
                ActivityHeatmapAggregator.aggregateComparison(faction1Days, faction2Days);
        vo.setFaction1AverageOnline(matrix.faction1Average());
        vo.setFaction2AverageOnline(matrix.faction2Average());
        vo.setBothObserved(matrix.bothObserved());
        vo.setSubtitle(display1 + " / " + display2
                + "｜仅对比有效活跃人数；Idle 不计入对比｜共同采样覆盖率: "
                + formatPercent(calculateCoverage(matrix.totalCommonObservedSlots(), range)));
        fillCommonMetadata(vo, matrix.totalCommonObservedSlots(), matrix.actualDays(),
                matrix.observedDowCount(), matrix.legacyIncluded(), range, false);
        return vo;
    }

    // ==================== 共同元数据填充 ====================

    /**
     * 填充覆盖率、hasData、部分覆盖提示与 legacy 标记
     * <p>
     * 同时满足"采样日不足"与"星期行不足"时优先显示采样日文案，避免两条低价值重复提示。
     *
     * @param vo                 目标 VO
     * @param totalObservedSlots observed 槽总数
     * @param actualDays         存在采样的自然日数量
     * @param observedRowCount   存在采样的网格行数量
     * @param legacyIncluded     是否包含 V2 legacy 采样
     * @param range              查询日期范围
     * @param todayNoticeEnabled 是否允许追加"当日数据尚未完整"；对比图文案保持既有形态故传 false
     */
    private void fillCommonMetadata(BaseActivityHeatmapVO vo,
                                    int totalObservedSlots, int actualDays, int observedRowCount,
                                    boolean legacyIncluded, ActivityQueryRange range,
                                    boolean todayNoticeEnabled) {
        vo.setCoverage(calculateCoverage(totalObservedSlots, range));
        vo.setHasData(totalObservedSlots > 0);
        vo.setLegacyDataIncluded(legacyIncluded);
        if (!vo.isHasData()) {
            return;
        }
        vo.setNoticeMessage(buildNoticeMessage(
                range, actualDays, observedRowCount, legacyIncluded, todayNoticeEnabled));
    }

    /**
     * 拼接副标题第二行提示，顺序为"数据不完整 → legacy"
     *
     * @param range              查询日期范围
     * @param actualDays         存在采样的自然日数量
     * @param observedRowCount   存在采样的网格行数量
     * @param legacyIncluded     是否包含 V2 legacy 采样
     * @param todayNoticeEnabled 是否允许追加"当日数据尚未完整"
     * @return 提示文案；无任何提示时返回 null
     */
    private static String buildNoticeMessage(ActivityQueryRange range, int actualDays,
                                             int observedRowCount, boolean legacyIncluded,
                                             boolean todayNoticeEnabled) {
        List<String> notices = new ArrayList<>(3);
        if (range.caliber() == ActivityCaliberEnum.TYPICAL_WEEK) {
            String partialCoverageNotice = buildPartialCoverageNotice(actualDays, observedRowCount);
            if (partialCoverageNotice != null) {
                notices.add(partialCoverageNotice);
            }
        }
        if (todayNoticeEnabled && isWindowEndingToday(range)) {
            notices.add(TODAY_INCOMPLETE_NOTICE);
        }
        if (legacyIncluded) {
            notices.add(LEGACY_NOTICE);
        }
        return notices.isEmpty() ? null : String.join(NOTICE_SEPARATOR, notices);
    }

    /**
     * 判断窗口是否包含今天：锚点即窗口最后一天且不晚于今天，故等价于锚点就是今天
     *
     * @param range 查询日期范围
     * @return true 表示窗口包含今天
     */
    private static boolean isWindowEndingToday(ActivityQueryRange range) {
        return range.endDate().isEqual(LocalDate.now(TornActivityCollectService.HEATMAP_ZONE));
    }

    /**
     * 构建副标题第一行的口径与窗口前缀
     *
     * @param range 查询日期范围
     * @return 形如 {@code 口径：单日（15 分钟）｜2026-08-01 ~ 2026-08-01｜} 的前缀
     */
    private static String buildCaliberSubtitlePrefix(ActivityQueryRange range) {
        return "口径：" + range.caliber().keyword() + "（" + range.caliber().cellSpanLabel() + "）"
                + SUBTITLE_SEPARATOR + range.startDate() + " ~ " + range.endDate() + SUBTITLE_SEPARATOR;
    }

    /**
     * 构建部分覆盖提示；覆盖满足门槛时返回 null
     *
     * @param actualDays       存在采样的自然日数量
     * @param observedRowCount 存在采样的网格行数量
     * @return 提示文案，无提示时返回 null
     */
    private static String buildPartialCoverageNotice(int actualDays, int observedRowCount) {
        if (actualDays < MIN_SAMPLE_DAYS_NOTICE) {
            return "该时间范围仅覆盖 " + actualDays + " 个采样日，热力图仅供参考";
        }
        if (observedRowCount < FULL_DOW_COUNT) {
            return "该时间范围覆盖不完整（已覆盖 " + observedRowCount + "/7 个星期），热力图仅供参考";
        }
        return null;
    }

    /**
     * 计算覆盖率（实际 observed 槽数 / 查询窗口理论槽数）
     *
     * @param totalObservedSlots observed 槽总数
     * @param range              查询日期范围
     * @return 覆盖率
     */
    private static double calculateCoverage(int totalObservedSlots, ActivityQueryRange range) {
        long theoreticalSlots = (long) range.totalDays() * SLOTS_PER_DAY;
        return theoreticalSlots > 0 ? (double) totalObservedSlots / theoreticalSlots : 0;
    }

    /**
     * 校验公共查询入口参数，避免无效 ID 造成无意义数据源压力
     *
     * @param targetId 查询目标 ID
     */
    private static void validateQueryTarget(long targetId) {
        if (targetId <= 0) {
            throw new IllegalArgumentException("查询目标 ID 必须为正数");
        }
    }

    /**
     * 格式化比例为整数百分比文字
     *
     * @param ratio 比例值 [0, 1]
     * @return 百分比文字，如 "38%"
     */
    private static String formatPercent(double ratio) {
        return (int) Math.round(ratio * 100) + "%";
    }

    // ==================== 标题与名称 ====================

    /**
     * 构建个人热力图标题
     *
     * @param userId 用户 ID
     * @return 标题
     */
    private String buildPersonalTitle(long userId) {
        String userName = getUserName(userId);
        return userName != null
                ? userName + " [" + userId + HEATMAP_TITLE_SUFFIX
                : "用户 [" + userId + HEATMAP_TITLE_SUFFIX;
    }

    /**
     * 构建帮派热力图标题
     *
     * @param factionId 帮派 ID
     * @return 标题
     */
    private String buildFactionTitle(long factionId) {
        String factionName = getFactionName(factionId);
        return factionName != null
                ? factionName + " [" + factionId + HEATMAP_TITLE_SUFFIX
                : "帮派 [" + factionId + HEATMAP_TITLE_SUFFIX;
    }

    /**
     * 构建显示名称：有名称时 "名称 [ID]"，无名称时仅 ID
     *
     * @param factionId 帮派 ID
     * @return 显示名称
     */
    private String buildDisplayName(long factionId) {
        String name = getFactionName(factionId);
        return name != null ? name + " [" + factionId + "]" : String.valueOf(factionId);
    }

    /**
     * 从 Redis 名称缓存获取用户名，不存在返回 null
     *
     * @param userId 用户 ID
     * @return 用户名或 null
     */
    private String getUserName(long userId) {
        Object name = redisTemplate.opsForHash().get(
                ActivityRedisKeys.USER_NAME_CACHE_KEY, ActivityRedisKeys.userNameField(userId));
        return name != null ? name.toString() : null;
    }

    /**
     * 从 Redis 名称缓存获取帮派名，不存在返回 null
     *
     * @param factionId 帮派 ID
     * @return 帮派名或 null
     */
    private String getFactionName(long factionId) {
        Object name = redisTemplate.opsForHash().get(
                ActivityRedisKeys.FACTION_NAME_CACHE_KEY, ActivityRedisKeys.factionNameField(factionId));
        return name != null ? name.toString() : null;
    }
}
