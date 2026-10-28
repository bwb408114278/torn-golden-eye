package pn.torn.goldeneye.torn.service.faction.oc.recommend.rank;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * OC开动紧迫度评分。
 *
 * <p>只回答一个问题：这支队还要等多久才开始走进度。越早开动，被占用的人天越早回收，
 * 全帮可用人力越多，因此紧迫度是「单位人天产出」的时间折扣系数，取值0.10至1.00，
 * 停转队为1.00、新队为0.95。曲线沿用AC轮之前的业务口径（停转100分、新队95分、
 * 6小时内95至98、24小时65、48小时35、72小时20、更远10至20），
 * 该口径由用户2026.10.25确认保留，不得以「重新标定」为由改写分段。</p>
 *
 * <p>紧迫度只取决于ready_time与当前时刻，与成员能力、帮派配置无关，
 * 因此推荐与分配两个入口对同一支队的紧迫度完全一致。</p>
 *
 * @author Bai
 * @version 1.9.4
 * @since 2026.10.25
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public final class OcRecommendStartUrgency {
    /**
     * 紧迫度小数位数。
     */
    private static final int SCALE = 4;
    /**
     * 时间分满分，也是紧迫度换算的分母。
     */
    private static final BigDecimal FULL_SCORE = new BigDecimal("100");
    /**
     * 新队时间分：无人加入时开动时间不可观测，按「略低于临开动队」处理。
     */
    private static final BigDecimal NEW_TEAM_SCORE = new BigDecimal("95");
    /**
     * 6小时以内的时间分上限。
     */
    private static final BigDecimal WITHIN_SIX_HOURS_CEILING = new BigDecimal("100");
    /**
     * 6小时以内时间分的下限。
     */
    private static final BigDecimal WITHIN_SIX_HOURS_FLOOR = new BigDecimal("95");
    /**
     * 6小时以内每小时衰减分。
     */
    private static final BigDecimal WITHIN_SIX_HOURS_SLOPE = new BigDecimal("2");
    /**
     * 24小时以内的时间分上限。
     */
    private static final BigDecimal WITHIN_DAY_CEILING = new BigDecimal("95");
    /**
     * 24小时以内时间分的下限。
     */
    private static final BigDecimal WITHIN_DAY_FLOOR = new BigDecimal("65");
    /**
     * 24小时以内每小时衰减分。
     */
    private static final BigDecimal WITHIN_DAY_SLOPE = new BigDecimal("1.67");
    /**
     * 48小时以内的时间分上限。
     */
    private static final BigDecimal WITHIN_TWO_DAYS_CEILING = new BigDecimal("65");
    /**
     * 48小时以内时间分的下限。
     */
    private static final BigDecimal WITHIN_TWO_DAYS_FLOOR = new BigDecimal("35");
    /**
     * 48小时以内每小时衰减分。
     */
    private static final BigDecimal WITHIN_TWO_DAYS_SLOPE = new BigDecimal("1.25");
    /**
     * 72小时以内的时间分上限。
     */
    private static final BigDecimal WITHIN_THREE_DAYS_CEILING = new BigDecimal("35");
    /**
     * 72小时以内时间分的下限。
     */
    private static final BigDecimal WITHIN_THREE_DAYS_FLOOR = new BigDecimal("20");
    /**
     * 72小时以内每小时衰减分。
     */
    private static final BigDecimal WITHIN_THREE_DAYS_SLOPE = new BigDecimal("0.5");
    /**
     * 72小时以上的时间分上限。
     */
    private static final BigDecimal BEYOND_CEILING = new BigDecimal("20");
    /**
     * 72小时以上时间分的下限，保证紧迫度永远为正，不会把候选压成0分。
     */
    private static final BigDecimal BEYOND_FLOOR = new BigDecimal("10");
    /**
     * 72小时以上每小时衰减分。
     */
    private static final BigDecimal BEYOND_SLOPE = new BigDecimal("0.2");

    /**
     * 计算候选岗位的开动紧迫度。
     *
     * @param oc  OC数据，为空或ready_time为空时按新队处理
     * @param now 本次推荐的时间基准，为空时按新队处理，避免调用方传空破坏排序
     * @return 开动紧迫度，取值0.10至1.00
     */
    public static BigDecimal urgencyOf(TornFactionOcDO oc, LocalDateTime now) {
        return timeScore(oc, now).divide(FULL_SCORE, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算时间分，分段口径与AC轮之前的实现逐段一致。
     *
     * @param oc  OC数据
     * @param now 本次推荐的时间基准
     * @return 时间分，10至100
     */
    private static BigDecimal timeScore(TornFactionOcDO oc, LocalDateTime now) {
        if (oc == null || now == null || oc.getReadyTime() == null) {
            return NEW_TEAM_SCORE;
        }

        long hours = hoursUntilStop(oc.getReadyTime(), now);
        if (hours <= 0) {
            return FULL_SCORE;
        }
        if (hours <= 6) {
            return decay(WITHIN_SIX_HOURS_CEILING, hours, WITHIN_SIX_HOURS_SLOPE).max(WITHIN_SIX_HOURS_FLOOR);
        }
        if (hours <= 24) {
            return decay(WITHIN_DAY_CEILING, hours - 6, WITHIN_DAY_SLOPE).max(WITHIN_DAY_FLOOR);
        }
        if (hours <= 48) {
            return decay(WITHIN_TWO_DAYS_CEILING, hours - 24, WITHIN_TWO_DAYS_SLOPE)
                    .max(WITHIN_TWO_DAYS_FLOOR);
        }
        if (hours <= 72) {
            return decay(WITHIN_THREE_DAYS_CEILING, hours - 48, WITHIN_THREE_DAYS_SLOPE)
                    .max(WITHIN_THREE_DAYS_FLOOR);
        }
        return decay(BEYOND_CEILING, hours - 72, BEYOND_SLOPE).max(BEYOND_FLOOR);
    }

    /**
     * 计算距离停转的小时数，已过预计开动时间返回0。
     *
     * @param readyTime 预计开动时间
     * @param now       本次推荐的时间基准
     * @return 距离停转的小时数，非负
     */
    private static long hoursUntilStop(LocalDateTime readyTime, LocalDateTime now) {
        if (now.isAfter(readyTime)) {
            return 0L;
        }
        return Duration.between(now, readyTime).toHours() + 1;
    }

    /**
     * 按斜率线性衰减时间分。
     *
     * @param base  该分段的起始分
     * @param hours 该分段内已经过去的小时数
     * @param slope 每小时衰减分
     * @return 衰减后的时间分
     */
    private static BigDecimal decay(BigDecimal base, long hours, BigDecimal slope) {
        return base.subtract(slope.multiply(BigDecimal.valueOf(hours)));
    }
}
