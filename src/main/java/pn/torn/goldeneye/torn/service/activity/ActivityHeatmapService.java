package pn.torn.goldeneye.torn.service.activity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.torn.model.activity.ActivityHeatmapVO;

import java.time.DayOfWeek;
import java.time.LocalDate;
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
    private static final int MIN_DATA_DAYS = 7;
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

        double[][] heatmap = vo.getHeatmap();
        aggregateMembers(heatmap, members, days);

        int dataDays = countAnyMemberDays(members, days);
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

        double[][] heatmap = vo.getHeatmap();
        aggregateDiff(heatmap, m1, m2, days);

        int dataDays = Math.min(countAnyMemberDays(m1, days), countAnyMemberDays(m2, days));
        vo.setDataSufficient(dataDays >= MIN_DATA_DAYS);
        if (!vo.isDataSufficient()) {
            String name = dataDays == countAnyMemberDays(m1, days) ? "帮派 " + faction2Id : "帮派 " + faction1Id;
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
        int actualDays = 0;
        for (int d = 0; d < days; d++) {
            LocalDate date = today.minusDays(d);
            String key = TornActivityCollectService.buildRedisKey(userId, date);
            Boolean hasKey = redisTemplate.hasKey(key);
            if (!Boolean.TRUE.equals(hasKey)) continue;
            actualDays++;

            int dow = dowIndex(date.getDayOfWeek());
            for (int h = 0; h < 24; h++) {
                long startBit = (long) h * SAMPLES_PER_HOUR;
                long endBit = startBit + SAMPLES_PER_HOUR - 1;
                byte[] keyBytes = key.getBytes();
                Long bits = redisTemplate.execute((RedisCallback<Long>) conn ->
                        conn.stringCommands().bitCount(keyBytes, startBit, endBit));
                heatmap[dow][h] += (bits != null ? bits : 0);
            }
        }
        return actualDays;
    }

    /**
     * 聚合帮派所有成员的活跃度到热力图矩阵（人均在线人数）
     *
     * @param heatmap   热力图矩阵
     * @param memberIds 帮派成员 ID 集合
     * @param days      查询天数
     */
    private void aggregateMembers(double[][] heatmap, Set<String> memberIds, int days) {
        LocalDate today = LocalDate.now();
        for (int d = 0; d < days; d++) {
            LocalDate date = today.minusDays(d);
            int dow = dowIndex(date.getDayOfWeek());
            for (int h = 0; h < 24; h++) {
                long startBit = (long) h * SAMPLES_PER_HOUR;
                long endBit = startBit + SAMPLES_PER_HOUR - 1;
                long total = 0;
                int count = 0;
                for (String uid : memberIds) {
                    String key = TornActivityCollectService.buildRedisKey(Long.parseLong(uid), date);
                    byte[] keyBytes = key.getBytes();
                    Long bits = redisTemplate.execute((RedisCallback<Long>) conn ->
                            conn.stringCommands().bitCount(keyBytes, startBit, endBit));
                    if (bits != null) {
                        total += bits;
                        count++;
                    }
                }
                heatmap[dow][h] += count > 0 ? (double) total / count / SAMPLES_PER_HOUR : 0;
            }
        }
        normalizeByDow(heatmap, days);
    }

    /**
     * 聚合两帮派活跃度差异到热力图矩阵（帮派1 - 帮派2）
     *
     * @param heatmap 热力图矩阵
     * @param m1      帮派1成员 ID 集合
     * @param m2      帮派2成员 ID 集合
     * @param days    查询天数
     */
    private void aggregateDiff(double[][] heatmap, Set<String> m1, Set<String> m2, int days) {
        LocalDate today = LocalDate.now();
        for (int d = 0; d < days; d++) {
            LocalDate date = today.minusDays(d);
            int dow = dowIndex(date.getDayOfWeek());
            for (int h = 0; h < 24; h++) {
                long s = (long) h * SAMPLES_PER_HOUR;
                long e = s + SAMPLES_PER_HOUR - 1;
                double avg1 = avgBits(m1, date, s, e);
                double avg2 = avgBits(m2, date, s, e);
                heatmap[dow][h] += avg1 - avg2;
            }
        }
        normalizeByDow(heatmap, days);
    }

    /**
     * 计算指定成员集合在某日某时段的平均活跃比特数
     *
     * @param members 成员 ID 集合
     * @param date    日期
     * @param start   Bitmap 起始位
     * @param end     Bitmap 结束位
     * @return 该时段的人均活跃采样数
     */
    private double avgBits(Set<String> members, LocalDate date, long start, long end) {
        long total = 0;
        int count = 0;
        for (String uid : members) {
            String key = TornActivityCollectService.buildRedisKey(Long.parseLong(uid), date);
            byte[] keyBytes = key.getBytes();
            Long bits = redisTemplate.execute((RedisCallback<Long>) conn ->
                    conn.stringCommands().bitCount(keyBytes, start, end));
            if (bits != null) {
                total += bits;
                count++;
            }
        }
        return count > 0 ? (double) total / count / SAMPLES_PER_HOUR : 0;
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

    /**
     * 统计帮派成员中至少有一天活动数据的天数
     *
     * @param members 帮派成员 ID 集合
     * @param days    查询天数
     * @return 有数据的天数
     */
    private int countAnyMemberDays(Set<String> members, int days) {
        if (members.isEmpty()) return 0;
        String first = members.iterator().next();
        LocalDate today = LocalDate.now();
        int c = 0;
        for (int d = 0; d < days; d++) {
            String key = TornActivityCollectService.buildRedisKey(Long.parseLong(first), today.minusDays(d));
            Boolean hasKey = redisTemplate.hasKey(key);
            if (Boolean.TRUE.equals(hasKey)) c++;
        }
        return c;
    }
}
