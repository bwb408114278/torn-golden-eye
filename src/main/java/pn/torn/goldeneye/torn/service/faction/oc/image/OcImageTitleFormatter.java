package pn.torn.goldeneye.torn.service.faction.oc.image;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.torn.model.faction.oc.image.OcImageTimeStatusEnum;
import pn.torn.goldeneye.torn.service.faction.oc.OcPreparationTimeCalculator;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * OC 表格图片标题时间解析服务。
 * <p>
 * 统一封装 {@code readyTime} 的时间文案，按照“停转 &gt; 空转 &gt; 执行”互斥输出。
 * {@code readyTime} 是串行准备链的结束时间而不是实际执行时间：Planning 不代表立即执行，
 * 只有剩余时间严格超过 {@link #IDLE_THRESHOLD} 才表示准备链上还存在后续空转阶段。
 * <p>
 * 24 小时阈值判定必须使用完整时间精度，不得先把时间截断到分钟再比较；
 * 分钟截断只允许在状态确定后用于倒计时文案展示，保证同一分钟内文案稳定。
 * 计划执行时间委托 {@link OcPreparationTimeCalculator}，与完成延误通知共用同一权威实现。
 *
 * @author Bai
 * @version 1.5.2
 * @since 2026.08.30
 */
@Component
public class OcImageTitleFormatter {

    /**
     * 剩余时间严格超过该阈值才展示“还需空转”。
     */
    private static final Duration IDLE_THRESHOLD = Duration.ofHours(24);

    /**
     * 预计执行时间格式。
     */
    private static final DateTimeFormatter HH_MM_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * 解析 OC 标题时间状态。
     * <p>
     * 规则顺序：Recruiting 已过 {@code readyTime} 优先返回已停转；随后按完整时间精度判断剩余时间
     * 是否严格超过 24 小时（空转）；Recruiting 未超期归入停转倒计时；Planning 只要剩余不超过
     * 24 小时一律归入预计执行，即使已过 {@code readyTime} 也不返回空文案。
     *
     * @param status    OC 状态
     * @param readyTime OC 准备链结束时间；为 null 时无时间文案
     * @param now       当前时间
     * @return 唯一时间状态；无时间或非目标状态返回 {@link OcImageTimeStatusEnum#NONE}
     */
    public OcImageTimeStatusEnum resolve(String status, LocalDateTime readyTime, LocalDateTime now) {
        if (readyTime == null || now == null || !isSupportedStatus(status)) {
            return OcImageTimeStatusEnum.NONE;
        }

        if (isRecruiting(status) && now.isAfter(readyTime)) {
            return OcImageTimeStatusEnum.STOPPED;
        }

        if (isOverIdleThreshold(readyTime, now)) {
            return OcImageTimeStatusEnum.IDLE;
        }

        return isRecruiting(status)
                ? OcImageTimeStatusEnum.STOP_COUNTDOWN
                : OcImageTimeStatusEnum.PLANNED;
    }

    /**
     * 输出 OC 标题时间文案。
     *
     * @param status    OC 状态
     * @param readyTime OC 准备链结束时间；为 null 时返回空字符串
     * @param now       当前时间
     * @return 约定时间文案；无文案时返回空字符串
     */
    public String format(String status, LocalDateTime readyTime, LocalDateTime now) {
        OcImageTimeStatusEnum timeStatus = resolve(status, readyTime, now);
        return switch (timeStatus) {
            case STOPPED -> "已停转";
            case STOP_COUNTDOWN -> formatStopCountdown(readyTime, now);
            case IDLE -> formatIdle(readyTime, now);
            case PLANNED -> formatPlanned(readyTime);
            case NONE -> "";
        };
    }

    /**
     * 使用完整时间精度判断剩余准备链时间是否严格超过空转阈值。
     *
     * @param readyTime OC 准备链结束时间
     * @param now       当前时间
     * @return true 表示剩余时间严格大于 24 小时
     */
    private boolean isOverIdleThreshold(LocalDateTime readyTime, LocalDateTime now) {
        return Duration.between(now, readyTime).compareTo(IDLE_THRESHOLD) > 0;
    }

    /**
     * 按分钟截断计算倒计时分钟数，只在状态确定后用于文案展示，避免秒数导致同一分钟内文案抖动。
     */
    private long deltaMinutes(LocalDateTime readyTime, LocalDateTime now) {
        return ChronoUnit.MINUTES.between(
                now.truncatedTo(ChronoUnit.MINUTES),
                readyTime.truncatedTo(ChronoUnit.MINUTES));
    }

    private String formatStopCountdown(LocalDateTime readyTime, LocalDateTime now) {
        long minutes = Math.max(0, deltaMinutes(readyTime, now));
        return "%d小时%02d分后停转".formatted(toHours(minutes), toRemainderMinutes(minutes));
    }

    private String formatIdle(LocalDateTime readyTime, LocalDateTime now) {
        long minutes = Math.max(0, deltaMinutes(readyTime, now));
        return "还需空转%d小时%02d分钟".formatted(toHours(minutes), toRemainderMinutes(minutes));
    }

    private String formatPlanned(LocalDateTime readyTime) {
        LocalDateTime plannedTime = OcPreparationTimeCalculator.calculatePlannedTime(readyTime);
        return "预计" + plannedTime.format(HH_MM_FORMATTER) + "开始执行";
    }

    private long toHours(long minutes) {
        return minutes / 60;
    }

    private long toRemainderMinutes(long minutes) {
        return minutes % 60;
    }

    private boolean isRecruiting(String status) {
        return TornOcStatusEnum.RECRUITING.getCode().equals(status);
    }

    private boolean isSupportedStatus(String status) {
        return isRecruiting(status) || TornOcStatusEnum.PLANNING.getCode().equals(status);
    }
}
