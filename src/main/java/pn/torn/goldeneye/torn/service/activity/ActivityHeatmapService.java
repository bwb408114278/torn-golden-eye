package pn.torn.goldeneye.torn.service.activity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.torn.model.activity.ActivityHeatmapVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberDTO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberListVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberVO;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 活跃度热力图数据服务
 * <p>
 * 查询 Redis BITMAP 数据，构建 7×24 热力图矩陣。
 * 每天分为 24 个小时段，每段 4 个 15 分钟槽位（共 96 bit）。
 *
 * <pre>
 * 个人热力图：
 *   cellValue = totalActiveBits / (dayCount[dayOfWeek] × 4)
 *   → 活跃比例 0.0~1.0，渲染器展示为百分比
 *
 * 帮派热力图：
 *   cellValue = totalActiveBits / (dayCount[dayOfWeek] × memberCount × 4)
 *   → 成员平均活跃比例 0.0~1.0
 * </pre>
 *
 * @author Bai
 * @version 1.2.9
 * @since 2026.07.07
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityHeatmapService {
    private final StringRedisTemplate stringRedisTemplate;
    private final TornActivityCollectService tornActivityCollectService;
    private final TornApi tornApi;
    /**
     * Redis key 不存在或该位未设置时 GetBit 返回 false
     */
    private static final int SAMPLES_PER_HOUR = 4;

    // ==================== 个人热力图 ====================

    /**
     * 查询个人活跃度热力图
     * <p>
     * 遍历最近 {@code days} 天，对每天的 24 个小时各执行 4 次 GetBit，
     * 按星期几聚合，计算活跃比例。
     *
     * @param userId 用户 ID
     * @param days   统计天数（>= 1）
     * @return 热力图 VO，{@code factionMode = false}
     */
    public ActivityHeatmapVO queryPersonalHeatmap(long userId, int days) {
        String title = "用户 " + userId;
        ActivityHeatmapVO vo = new ActivityHeatmapVO();
        vo.setTitle(title);
        vo.setFactionMode(false);
        vo.setTotalDays(days);
        vo.setSamplesPerHour(SAMPLES_PER_HOUR);

        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(days - 1L);

        int[][] totalBits = new int[7][24];
        int[] dayCounts = new int[7];
        int totalKeysFound = 0;

        for (LocalDate date = startDate; !date.isAfter(today); date = date.plusDays(1)) {
            String key = TornActivityCollectService.buildRedisKey(userId, date);
            int dayIndex = toDayOfWeekIndex(date.getDayOfWeek());
            dayCounts[dayIndex]++;

            boolean keyHasData = false;

            for (int hour = 0; hour < 24; hour++) {
                int startBit = hour * SAMPLES_PER_HOUR;
                int bitsActive = countBitsInRange(key, startBit, SAMPLES_PER_HOUR);
                if (bitsActive > 0) {
                    keyHasData = true;
                }
                totalBits[dayIndex][hour] += bitsActive;
            }

            if (keyHasData) {
                totalKeysFound++;
            }
        }

        // 计算每个 (dayOfWeek, hour) 的活跃比例 0.0~1.0
        double[][] heatmap = new double[7][24];
        for (int dow = 0; dow < 7; dow++) {
            if (dayCounts[dow] == 0) {
                continue;
            }
            double divisor = dayCounts[dow] * (double) SAMPLES_PER_HOUR;
            for (int hour = 0; hour < 24; hour++) {
                heatmap[dow][hour] = totalBits[dow][hour] / divisor;
            }
        }
        vo.setHeatmap(heatmap);

        // 数据充分性检查：至少需要 7 天有数据
        vo.setDataSufficient(totalKeysFound >= 7);
        if (!vo.isDataSufficient()) {
            vo.setInsufficientMessage("数据不足：仅采集到 " + totalKeysFound + " 天数据，需至少 7 天");
        }

        log.debug("个人热力图: userId={}, days={}, keysFound={}, sufficient={}",
                userId, days, totalKeysFound, vo.isDataSufficient());
        return vo;
    }

    // ==================== 帮派热力图 ====================

    /**
     * 查询帮派活跃度热力图
     * <p>
     * 先通过 TornApi 拉取帮派成员列表，再对每个成员聚合其 BITMAP 数据。
     * 需要该帮派在当前追踪范围内，否则返回 {@code dataSufficient = false}。
     *
     * @param factionId 帮派 ID
     * @param days      统计天数（>= 1）
     * @return 热力图 VO，{@code factionMode = true}
     */
    public ActivityHeatmapVO queryFactionHeatmap(long factionId, int days) {
        String title = "帮派 " + factionId;
        ActivityHeatmapVO vo = new ActivityHeatmapVO();
        vo.setTitle(title);
        vo.setFactionMode(true);
        vo.setTotalDays(days);
        vo.setSamplesPerHour(SAMPLES_PER_HOUR);

        // 1. 校验帮派是否在追踪范围内
        List<Long> trackedIds = tornActivityCollectService.getTrackedFactionIds();
        if (!trackedIds.contains(factionId)) {
            vo.setHeatmap(new double[7][24]);
            vo.setDataSufficient(false);
            vo.setInsufficientMessage("该帮派未在追踪范围内");
            return vo;
        }

        // 2. 拉取帮派成员列表
        List<Long> memberIds;
        try {
            TornFactionMemberDTO param = new TornFactionMemberDTO(factionId);
            TornFactionMemberListVO resp = tornApi.sendRequest(param, TornFactionMemberListVO.class);

            if (resp == null || CollectionUtils.isEmpty(resp.getMembers())) {
                vo.setHeatmap(new double[7][24]);
                vo.setDataSufficient(false);
                vo.setInsufficientMessage("该帮派无成员数据");
                return vo;
            }

            memberIds = new ArrayList<>();
            for (TornFactionMemberVO member : resp.getMembers()) {
                if (member.getId() != null) {
                    memberIds.add(member.getId());
                }
            }

            if (memberIds.isEmpty()) {
                vo.setHeatmap(new double[7][24]);
                vo.setDataSufficient(false);
                vo.setInsufficientMessage("该帮派无有效成员");
                return vo;
            }
        } catch (Exception e) {
            log.error("获取帮派 {} 成员列表失败", factionId, e);
            vo.setHeatmap(new double[7][24]);
            vo.setDataSufficient(false);
            vo.setInsufficientMessage("获取帮派成员列表失败: " + e.getMessage());
            return vo;
        }

        int memberCount = memberIds.size();
        log.info("帮派 {} 成员数: {}", factionId, memberCount);

        // 3. 遍历日期 × 成员 × 小时，聚合 BitCount
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(days - 1L);

        int[][] totalBits = new int[7][24];
        int[] dayCounts = new int[7];

        for (LocalDate date = startDate; !date.isAfter(today); date = date.plusDays(1)) {
            int dayIndex = toDayOfWeekIndex(date.getDayOfWeek());
            dayCounts[dayIndex]++;

            for (int hour = 0; hour < 24; hour++) {
                int startBit = hour * SAMPLES_PER_HOUR;
                int bitsActiveSum = 0;

                for (Long memberId : memberIds) {
                    String key = TornActivityCollectService.buildRedisKey(memberId, date);
                    bitsActiveSum += countBitsInRange(key, startBit, SAMPLES_PER_HOUR);
                }

                totalBits[dayIndex][hour] += bitsActiveSum;
            }
        }

        // 4. 计算每个 (dayOfWeek, hour) 的成员平均活跃比例
        double[][] heatmap = new double[7][24];
        for (int dow = 0; dow < 7; dow++) {
            if (dayCounts[dow] == 0) {
                continue;
            }
            double divisor = dayCounts[dow] * (double) memberCount * SAMPLES_PER_HOUR;
            for (int hour = 0; hour < 24; hour++) {
                heatmap[dow][hour] = totalBits[dow][hour] / divisor;
            }
        }
        vo.setHeatmap(heatmap);
        vo.setDataSufficient(true);

        log.debug("帮派热力图: factionId={}, members={}, days={}, sufficient=true",
                factionId, memberCount, days);
        return vo;
    }

    // ==================== 工具方法 ====================

    /**
     * 统计指定 key 中从 {@code startBit} 开始的 {@code count} 个 bit 中有多少个为 1。
     * <p>
     * 使用 Redis GetBit 逐位查询。若 key 不存在，GetBit 返回 false，归零参与计数。
     *
     * @param key      Redis bitmap key
     * @param startBit 起始位偏移量
     * @param count    要统计的位数
     * @return 置位数量 (0 ~ count)
     */
    private int countBitsInRange(String key, int startBit, int count) {
        int bitsActive = 0;
        for (int offset = startBit; offset < startBit + count; offset++) {
            Boolean isSet = stringRedisTemplate.opsForValue().getBit(key, offset);
            if (Boolean.TRUE.equals(isSet)) {
                bitsActive++;
            }
        }
        return bitsActive;
    }

    /**
     * 将 Java DayOfWeek 映射为星期一=0 ... 星期日=6 的索引。
     *
     * @param dayOfWeek Java DayOfWeek 枚举
     * @return 0=星期一, 6=星期日
     */
    private static int toDayOfWeekIndex(DayOfWeek dayOfWeek) {
        return dayOfWeek.getValue() - 1; // MONDAY=1 → 0, SUNDAY=7 → 6
    }

    // ==================== 帮派对比热力图 ====================

    /**
     * 帮派活跃度对比热力图
     * <p>
     * 计算两个帮派的活跃度差值热力图。
     * 每个 (dayOfWeek, hour) 格子的值为 faction1Avg - faction2Avg，
     * 正值表示帮派1更活跃，负值表示帮派2更活跃。
     *
     * @param faction1Id 帮派1 ID
     * @param faction2Id 帮派2 ID
     * @param days       统计天数（>= 1）
     * @return 热力图 VO，{@code compareMode = true}
     */
    public ActivityHeatmapVO compareFactions(long faction1Id, long faction2Id, int days) {
        ActivityHeatmapVO vo = new ActivityHeatmapVO();
        vo.setTitle("帮派 " + faction1Id + " vs " + faction2Id);
        vo.setFactionMode(false);
        vo.setCompareMode(true);
        vo.setFaction1Name("帮派 " + faction1Id);
        vo.setFaction2Name("帮派 " + faction2Id);
        vo.setTotalDays(days);
        vo.setSamplesPerHour(SAMPLES_PER_HOUR);

        // 1. 获取两个帮派的成员列表
        List<Long> faction1Members = fetchFactionMembers(faction1Id);
        if (CollectionUtils.isEmpty(faction1Members)) {
            vo.setHeatmap(new double[7][24]);
            vo.setDataSufficient(false);
            vo.setInsufficientMessage("无法获取帮派 " + faction1Id + " 成员列表");
            return vo;
        }

        List<Long> faction2Members = fetchFactionMembers(faction2Id);
        if (CollectionUtils.isEmpty(faction2Members)) {
            vo.setHeatmap(new double[7][24]);
            vo.setDataSufficient(false);
            vo.setInsufficientMessage("无法获取帮派 " + faction2Id + " 成员列表");
            return vo;
        }

        int memberCount1 = faction1Members.size();
        int memberCount2 = faction2Members.size();
        log.info("帮派对比: faction1={} ({}人), faction2={} ({}人)", faction1Id, memberCount1, faction2Id, memberCount2);

        // 2. 遍历日期 × 小时，聚合两个帮派各小时的总活跃 bit 数
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(days - 1L);

        int[][] totalBits1 = new int[7][24];
        int[][] totalBits2 = new int[7][24];
        int[] dayCounts = new int[7];

        for (LocalDate date = startDate; !date.isAfter(today); date = date.plusDays(1)) {
            int dayIndex = toDayOfWeekIndex(date.getDayOfWeek());
            dayCounts[dayIndex]++;

            for (int hour = 0; hour < 24; hour++) {
                int startBit = hour * SAMPLES_PER_HOUR;

                // 帮派1：汇总所有成员在该 (date, hour) 的活跃 bits
                int bits1Sum = 0;
                for (Long memberId : faction1Members) {
                    String key = TornActivityCollectService.buildRedisKey(memberId, date);
                    bits1Sum += countBitsInRange(key, startBit, SAMPLES_PER_HOUR);
                }
                totalBits1[dayIndex][hour] += bits1Sum;

                // 帮派2：汇总所有成员在该 (date, hour) 的活跃 bits
                int bits2Sum = 0;
                for (Long memberId : faction2Members) {
                    String key = TornActivityCollectService.buildRedisKey(memberId, date);
                    bits2Sum += countBitsInRange(key, startBit, SAMPLES_PER_HOUR);
                }
                totalBits2[dayIndex][hour] += bits2Sum;
            }
        }

        // 3. 计算每个 (dayOfWeek, hour) 的平均在线人数差值
        //    faction1Avg = totalBits1 / (dayCount × memberCount1 × SAMPLES_PER_HOUR)
        //    faction2Avg = totalBits2 / (dayCount × memberCount2 × SAMPLES_PER_HOUR)
        //    diff = faction1Avg - faction2Avg（正值 = faction1 更活跃）
        double[][] heatmap = new double[7][24];
        for (int dow = 0; dow < 7; dow++) {
            if (dayCounts[dow] == 0) {
                continue;
            }
            double divisor1 = dayCounts[dow] * (double) memberCount1 * SAMPLES_PER_HOUR;
            double divisor2 = dayCounts[dow] * (double) memberCount2 * SAMPLES_PER_HOUR;
            for (int hour = 0; hour < 24; hour++) {
                double faction1Avg = totalBits1[dow][hour] / divisor1;
                double faction2Avg = totalBits2[dow][hour] / divisor2;
                heatmap[dow][hour] = faction1Avg - faction2Avg;
            }
        }
        vo.setHeatmap(heatmap);

        // 4. 数据充分性检查：取每个帮派第一个成员，检查其数据天数 >= 7
        int dataDays1 = countDataDays(faction1Members.getFirst(), startDate, today);
        int dataDays2 = countDataDays(faction2Members.getFirst(), startDate, today);

        if (dataDays1 < 7 || dataDays2 < 7) {
            vo.setDataSufficient(false);
            StringBuilder msg = new StringBuilder("数据不足：");
            if (dataDays1 < 7) {
                msg.append("帮派 ").append(faction1Id).append(" 仅 ").append(dataDays1).append(" 天");
            }
            if (dataDays2 < 7) {
                if (dataDays1 < 7) {
                    msg.append("，");
                }
                msg.append("帮派 ").append(faction2Id).append(" 仅 ").append(dataDays2).append(" 天");
            }
            msg.append("（需各至少 7 天）");
            vo.setInsufficientMessage(msg.toString());
        } else {
            vo.setDataSufficient(true);
        }

        log.debug("帮派对比热力图: faction1={}, faction2={}, days={}, dataDays1={}, dataDays2={}, sufficient={}",
                faction1Id, faction2Id, days, dataDays1, dataDays2, vo.isDataSufficient());
        return vo;
    }

    /**
     * 获取帮派成员 ID 列表
     *
     * @param factionId 帮派 ID
     * @return 成员 ID 列表，获取失败返回空列表
     */
    private List<Long> fetchFactionMembers(long factionId) {
        try {
            TornFactionMemberDTO param = new TornFactionMemberDTO(factionId);
            TornFactionMemberListVO resp = tornApi.sendRequest(param, TornFactionMemberListVO.class);
            if (resp == null || CollectionUtils.isEmpty(resp.getMembers())) {
                return List.of();
            }
            List<Long> memberIds = new ArrayList<>();
            for (TornFactionMemberVO member : resp.getMembers()) {
                if (member.getId() != null) {
                    memberIds.add(member.getId());
                }
            }
            return memberIds;
        } catch (Exception e) {
            log.error("获取帮派 {} 成员列表失败", factionId, e);
            return List.of();
        }
    }

    /**
     * 统计某个成员在指定日期范围内有多少天有数据
     * <p>
     * 遍历日期范围，检查每天任意小时是否至少有一个活跃 bit。
     *
     * @param memberId  成员 ID
     * @param startDate 起始日期（含）
     * @param endDate   结束日期（含）
     * @return 有数据的天数
     */
    private int countDataDays(long memberId, LocalDate startDate, LocalDate endDate) {
        int dataDays = 0;
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            String key = TornActivityCollectService.buildRedisKey(memberId, date);
            // 检查该天是否有任意小时的活跃 bit
            int totalActive = 0;
            for (int hour = 0; hour < 24; hour++) {
                totalActive += countBitsInRange(key, hour * SAMPLES_PER_HOUR, SAMPLES_PER_HOUR);
            }
            if (totalActive > 0) {
                dataDays++;
            }
        }
        return dataDays;
    }
}
