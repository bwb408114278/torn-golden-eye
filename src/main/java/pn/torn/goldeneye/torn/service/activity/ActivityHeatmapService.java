package pn.torn.goldeneye.torn.service.activity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.torn.model.activity.ActivityHeatmapVO;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 活跃度热力图查询服务
 *
 * @author Bai
 * @version 1.2.9
 * @since 2026.07.07
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityHeatmapService {
    private static final int SAMPLES_PER_HOUR = 4;
    private static final int HOURS_PER_DAY = 24;
    static final int MIN_DATA_DAYS = 7;
    private static final String REDIS_MEMBERS_PREFIX = "faction:members:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 查询个人活跃度热力图
     *
     * @param userId 用户 ID
     * @param days   查询天数
     * @return 个人热力图数据
     */
    public ActivityHeatmapVO queryPersonalHeatmap(long userId, int days) {
        ActivityHeatmapVO vo = ActivityHeatmapVO.empty("用户 " + userId + " 活跃度热力图");
        vo.setFactionMode(false);
        vo.setTotalDays(days);
        vo.setSamplesPerHour(SAMPLES_PER_HOUR);

        double[][] heatmap = vo.getHeatmap();
        int actualDays = aggregatePersonal(userId, days, heatmap);

        normalizeByDow(heatmap, days);
        vo.setDataSufficient(actualDays >= MIN_DATA_DAYS);
        if (!vo.isDataSufficient()) {
            vo.setInsufficientMessage("⚠️ 数据不足（仅采集 " + actualDays + " 天），需至少 " + MIN_DATA_DAYS + " 天后才能生成有效热力图");
        }
        return vo;
    }

    /**
     * 查询帮派活跃度热力图（人均在线人数）
     *
     * @param factionId 帮派 ID
     * @param days      查询天数
     * @return 帮派热力图数据
     */
    public ActivityHeatmapVO queryFactionHeatmap(long factionId, int days) {
        Set<String> members = redisTemplate.opsForSet().members(REDIS_MEMBERS_PREFIX + factionId);
        if (members == null || members.isEmpty()) {
            ActivityHeatmapVO vo = ActivityHeatmapVO.empty("帮派 " + factionId);
            vo.setDataSufficient(false);
            vo.setInsufficientMessage("⚠️ 未找到帮派 " + factionId + " 的成员数据");
            return vo;
        }

        ActivityHeatmapVO vo = ActivityHeatmapVO.empty("帮派 " + factionId + " 活跃度热力图（在线人数）");
        vo.setFactionMode(true);
        vo.setTotalDays(days);
        vo.setSamplesPerHour(SAMPLES_PER_HOUR);

        BitmapAggregate aggregate = batchComputeMemberAverages(new ArrayList<>(members), days);
        aggregateMembers(vo.getHeatmap(), aggregate.dailyAverages(), days);

        int dataDays = aggregate.dataDays();
        vo.setDataSufficient(dataDays >= MIN_DATA_DAYS);
        if (!vo.isDataSufficient()) {
            vo.setInsufficientMessage("⚠️ 数据不足（仅采集 " + dataDays + " 天），需至少 " + MIN_DATA_DAYS + " 天后才能生成有效热力图");
        }
        return vo;
    }

    /**
     * 对比两个帮派的活跃度差异（帮派1 - 帮派2）
     *
     * @param faction1Id 帮派1 ID
     * @param faction2Id 帮派2 ID
     * @param days       查询天数
     * @return 对比热力图数据
     */
    public ActivityHeatmapVO compareFactions(long faction1Id, long faction2Id, int days) {
        Set<String> m1 = redisTemplate.opsForSet().members(REDIS_MEMBERS_PREFIX + faction1Id);
        Set<String> m2 = redisTemplate.opsForSet().members(REDIS_MEMBERS_PREFIX + faction2Id);

        if (m1 == null || m1.isEmpty() || m2 == null || m2.isEmpty()) {
            ActivityHeatmapVO vo = ActivityHeatmapVO.forComparison(faction1Id, "帮派 " + faction1Id, faction2Id, "帮派 " + faction2Id);
            vo.setDataSufficient(false);
            vo.setInsufficientMessage("⚠️ 未找到帮派成员数据");
            return vo;
        }

        ActivityHeatmapVO vo = ActivityHeatmapVO.forComparison(faction1Id, "帮派 " + faction1Id, faction2Id, "帮派 " + faction2Id);
        vo.setTotalDays(days);
        vo.setSamplesPerHour(SAMPLES_PER_HOUR);

        BitmapAggregate aggregate1 = batchComputeMemberAverages(new ArrayList<>(m1), days);
        BitmapAggregate aggregate2 = batchComputeMemberAverages(new ArrayList<>(m2), days);
        aggregateDiff(vo.getHeatmap(), aggregate1.dailyAverages(), aggregate2.dailyAverages(), days);

        int dataDays = Math.min(aggregate1.dataDays(), aggregate2.dataDays());
        vo.setDataSufficient(dataDays >= MIN_DATA_DAYS);
        if (!vo.isDataSufficient()) {
            String name = aggregate1.dataDays() <= aggregate2.dataDays()
                    ? "帮派 " + faction1Id : "帮派 " + faction2Id;
            vo.setInsufficientMessage("⚠️ 数据不足，" + name + " 仅采集 " + dataDays + " 天，需至少 " + MIN_DATA_DAYS + " 天");
        }
        return vo;
    }

    /**
     * 聚合个人活跃度数据到热力图矩阵
     *
     * @param userId  用户 ID
     * @param days    查询天数
     * @param heatmap 热力图矩阵
     * @return 实际有数据的天数
     */
    private int aggregatePersonal(long userId, int days, double[][] heatmap) {
        LocalDate today = LocalDate.now();
        List<byte[]> bitmaps = loadBitmaps(List.of(String.valueOf(userId)), days);
        int actualDays = 0;
        for (int d = 0; d < days; d++) {
            byte[] bitmap = bitmaps.get(d);
            if (bitmap == null) {
                continue;
            }
            actualDays++;

            int dow = dowIndex(today.minusDays(d).getDayOfWeek());
            for (int h = 0; h < HOURS_PER_DAY; h++) {
                heatmap[dow][h] += countActiveSamples(bitmap, h);
            }
        }
        return actualDays;
    }

    /**
     * 批量读取成员每日 Bitmap，并在 JVM 内计算每小时平均在线人数。
     * <p>
     * 每个成员每天只读取一个最多 12 字节的 Bitmap，避免按小时执行 BITCOUNT。
     *
     * @param memberIds 成员 ID 列表
     * @param days      查询天数
     * @return [day][hour] 平均在线人数，day=0 为今天
     */
    private BitmapAggregate batchComputeMemberAverages(List<String> memberIds, int days) {
        int memberCount = memberIds.size();
        List<byte[]> bitmaps = loadBitmaps(memberIds, days);
        double[][] avg = new double[days][HOURS_PER_DAY];
        int dataDays = 0;

        int index = 0;
        for (int d = 0; d < days; d++) {
            boolean hasData = false;
            for (int m = 0; m < memberCount; m++) {
                byte[] bitmap = bitmaps.get(index++);
                hasData |= bitmap != null;
                for (int h = 0; h < HOURS_PER_DAY; h++) {
                    avg[d][h] += (double) countActiveSamples(bitmap, h) / SAMPLES_PER_HOUR;
                }
            }
            if (hasData) {
                dataDays++;
            }
        }
        return new BitmapAggregate(avg, dataDays);
    }

    /**
     * 按日期、成员顺序批量读取每日 Bitmap。
     */
    private List<byte[]> loadBitmaps(List<String> memberIds, int days) {
        LocalDate today = LocalDate.now();
        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) conn -> {
            for (int d = 0; d < days; d++) {
                LocalDate date = today.minusDays(d);
                for (String userId : memberIds) {
                    String key = TornActivityCollectService.buildRedisKey(Long.parseLong(userId), date);
                    conn.stringCommands().get(key.getBytes());
                }
            }
            return null;
        }, RedisSerializer.byteArray());
        return mapPipelineResults(results);
    }

    static List<byte[]> mapPipelineResults(List<Object> results) {
        return results.stream()
                .map(result -> result instanceof byte[] bytes ? bytes : null)
                .toList();
    }

    /**
     * 按 Redis Bitmap 的 MSB-first 位序统计指定小时内的活跃采样数。
     */
    static int countActiveSamples(byte[] bitmap, int hour) {
        if (bitmap == null) {
            return 0;
        }
        int activeSamples = 0;
        int firstSlot = hour * SAMPLES_PER_HOUR;
        for (int slot = firstSlot; slot < firstSlot + SAMPLES_PER_HOUR; slot++) {
            int byteIndex = slot / Byte.SIZE;
            if (byteIndex >= bitmap.length) {
                continue;
            }
            int mask = 1 << (Byte.SIZE - 1 - slot % Byte.SIZE);
            if ((bitmap[byteIndex] & mask) != 0) {
                activeSamples++;
            }
        }
        return activeSamples;
    }

    /**
     * 聚合帮派所有成员的活跃度到热力图矩阵（人均在线人数）
     *
     * @param heatmap 热力图矩阵
     * @param avg     帮派成员 ID 集合
     * @param days    查询天数
     */
    private void aggregateMembers(double[][] heatmap, double[][] avg, int days) {
        LocalDate today = LocalDate.now();
        for (int d = 0; d < days; d++) {
            int dow = dowIndex(today.minusDays(d).getDayOfWeek());
            for (int h = 0; h < 24; h++) {
                heatmap[dow][h] += avg[d][h];
            }
        }
        normalizeByDow(heatmap, days);
    }

    /**
     * 聚合两帮派活跃度差异到热力图矩阵（帮派1 - 帮派2）
     *
     * @param heatmap 热力图矩阵
     * @param avg1    帮派1成员 ID 集合
     * @param avg2    帮派2成员 ID 集合
     * @param days    查询天数
     */
    private void aggregateDiff(double[][] heatmap, double[][] avg1, double[][] avg2, int days) {
        LocalDate today = LocalDate.now();
        for (int d = 0; d < days; d++) {
            int dow = dowIndex(today.minusDays(d).getDayOfWeek());
            for (int h = 0; h < 24; h++) {
                heatmap[dow][h] += avg1[d][h] - avg2[d][h];
            }
        }
        normalizeByDow(heatmap, days);
    }

    /**
     * 按星期几归一化热力图数据（除以该星期几出现的天数）
     *
     * @param heatmap   热力图矩阵
     * @param totalDays 总查询天数
     */
    private static void normalizeByDow(double[][] heatmap, int totalDays) {
        int[] dowCounts = countDaysOfWeek(totalDays);
        for (int dow = 0; dow < 7; dow++) {
            if (dowCounts[dow] > 0) {
                for (int h = 0; h < 24; h++) heatmap[dow][h] /= dowCounts[dow];
            }
        }
    }

    /**
     * 统计查询天数范围内每个星期几出现的次数
     *
     * @param totalDays 总查询天数
     * @return 长度为7的数组，索引0=周一
     */
    private static int[] countDaysOfWeek(int totalDays) {
        int[] c = new int[7];
        LocalDate today = LocalDate.now();
        for (int d = 0; d < totalDays; d++) c[dowIndex(today.minusDays(d).getDayOfWeek())]++;
        return c;
    }

    /**
     * 将 DayOfWeek 转换为热力图行索引（周一=0，周日=6）
     *
     * @param dow 星期几
     * @return 行索引 (0-6)
     */
    private static int dowIndex(DayOfWeek dow) {
        return dow == DayOfWeek.SUNDAY ? 6 : dow.getValue() - 1;
    }

    private record BitmapAggregate(double[][] dailyAverages, int dataDays) {
    }
}
