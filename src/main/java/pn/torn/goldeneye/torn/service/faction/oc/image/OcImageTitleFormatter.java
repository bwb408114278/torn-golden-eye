package pn.torn.goldeneye.torn.service.faction.oc.image;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.torn.model.faction.oc.image.OcImageTimeStatusEnum;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * OC 表格图片标题时间解析服务。
 * <p>
 * 统一封装 {@code readyTime} 的时间文案，按照“停转 > 空转 > 执行”互斥输出，
 * 并复用具项目“准备时间分钟截断加 1 分钟”的执行时间口径。
 *
 * @author Bai
 * @version 1.5.2
 * @since 2026.08.30
 */
@Component
public class OcImageTitleFormatter {

    /**
     * 单条准备链超过该分钟数才展示“还需空转”。
     */
    private static final long IDLE_THRESHOLD_MINUTES = 24 * 60L;

    /**
     * 预计执行时间格式。
     */
    private static final DateTimeFormatter HH_MM_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * 解析 OC 标题时间状态。
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

        if (TornOcStatusEnum.RECRUITING.getCode().equals(status) && now.isAfter(readyTime)) {
            return OcImageTimeStatusEnum.STOPPED;
        }

        if (TornOcStatusEnum.PLANNING.getCode().equals(status) && now.isAfter(readyTime)) {
            return OcImageTimeStatusEnum.NONE;
        }

        long deltaMinutes = deltaMinutes(readyTime, now);
        if (TornOcStatusEnum.RECRUITING.getCode().equals(status)) {
            return deltaMinutes <= IDLE_THRESHOLD_MINUTES
                    ? OcImageTimeStatusEnum.STOP_COUNTDOWN
                    : OcImageTimeStatusEnum.IDLE;
        }

        return deltaMinutes > IDLE_THRESHOLD_MINUTES
                ? OcImageTimeStatusEnum.IDLE
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
     * 按分钟截断计算倒计时分钟数，避免秒数导致同一分钟内文案抖动。
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

    /**
     * 计算OC计划完成时间。
     * <p>
     * 统一按项目完成延误通知口径：准备时间所在分钟截断后加 1 分钟。
     *
     * @param readyTime OC准备时间
     * @return 计划完成时间
     */
    public static LocalDateTime calculatePlannedTime(LocalDateTime readyTime) {
        return readyTime.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
    }

    private String formatPlanned(LocalDateTime readyTime) {
        LocalDateTime plannedTime = calculatePlannedTime(readyTime);
        return "预计" + plannedTime.format(HH_MM_FORMATTER) + "开始执行";
    }

    private long toHours(long minutes) {
        return minutes / 60;
    }

    private long toRemainderMinutes(long minutes) {
        return minutes % 60;
    }

    private boolean isSupportedStatus(String status) {
        return TornOcStatusEnum.RECRUITING.getCode().equals(status)
                || TornOcStatusEnum.PLANNING.getCode().equals(status);
    }
}
