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
 * OC图片标题时间文案格式化器。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
@Component
public class OcImageTitleFormatter {
    private static final Duration IDLE_THRESHOLD = Duration.ofHours(24);
    private static final DateTimeFormatter HH_MM_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * 根据OC状态和固定当前时间生成唯一时间文案。
     *
     * @param status    OC状态
     * @param readyTime OC准备链结束时间
     * @param now       图片构建时的当前时间
     * @return 时间文案；没有文案时返回空字符串
     */
    public String format(String status, LocalDateTime readyTime, LocalDateTime now) {
        OcImageTimeStatusEnum timeStatus = resolve(status, readyTime, now);
        return switch (timeStatus) {
            case STOPPED -> "已停转";
            case STOP_COUNTDOWN -> countdownText(readyTime, now) + "后停转";
            case IDLE -> formatIdle(readyTime, now);
            case PLANNED -> "预计" + OcPreparationTimeCalculator.calculatePlannedTime(readyTime)
                    .format(HH_MM_FORMATTER) + "开始执行";
            case NONE -> "";
        };
    }

    /**
     * 查询受控的标题时间描述，供组装层一次性获取副标题文案与徽章色调映射依据。
     * <p>
     * 只复用现有{@link #resolve(String, LocalDateTime, LocalDateTime)}和{@link #format(String, LocalDateTime, LocalDateTime)}
     * 逻辑，不复制时间状态机判定。
     *
     * @param status    OC状态
     * @param readyTime OC准备链结束时间
     * @param now       图片构建时的当前时间
     * @return 文字与时间状态的受控描述
     */
    public Description describe(String status, LocalDateTime readyTime, LocalDateTime now) {
        return new Description(format(status, readyTime, now), resolve(status, readyTime, now));
    }

    /**
     * 解析标题时间状态，不读取系统时钟。
     *
     * @param status    OC状态
     * @param readyTime OC准备链结束时间
     * @param now       图片构建时的当前时间
     * @return 时间状态
     */
    public OcImageTimeStatusEnum resolve(String status, LocalDateTime readyTime, LocalDateTime now) {
        if (readyTime == null || now == null || !isSupportedStatus(status)) {
            return OcImageTimeStatusEnum.NONE;
        }
        if (isRecruiting(status) && now.isAfter(readyTime)) {
            return OcImageTimeStatusEnum.STOPPED;
        }
        if (Duration.between(now, readyTime).compareTo(IDLE_THRESHOLD) > 0) {
            return OcImageTimeStatusEnum.IDLE;
        }
        return isRecruiting(status) ? OcImageTimeStatusEnum.STOP_COUNTDOWN : OcImageTimeStatusEnum.PLANNED;
    }

    /**
     * 格式化空转状态的剩余时间。
     *
     * @param readyTime OC准备链结束时间
     * @param now       图片构建时的当前时间
     * @return 空转剩余时间文案
     */
    private String formatIdle(LocalDateTime readyTime, LocalDateTime now) {
        long minutes = countdownMinutes(readyTime, now);
        return "还需空转%d小时%02d分钟".formatted(minutes / 60, minutes % 60);
    }

    /**
     * 格式化停转倒计时。
     *
     * @param readyTime OC准备链结束时间
     * @param now       图片构建时的当前时间
     * @return 停转倒计时文案
     */
    private String countdownText(LocalDateTime readyTime, LocalDateTime now) {
        long minutes = countdownMinutes(readyTime, now);
        return "%d小时%02d分".formatted(minutes / 60, minutes % 60);
    }

    /**
     * 计算当前时间到准备链结束时间之间的分钟数。
     *
     * @param readyTime OC准备链结束时间
     * @param now       图片构建时的当前时间
     * @return 不小于0的分钟数
     */
    private long countdownMinutes(LocalDateTime readyTime, LocalDateTime now) {
        return Math.max(0, ChronoUnit.MINUTES.between(
                now.truncatedTo(ChronoUnit.MINUTES), readyTime.truncatedTo(ChronoUnit.MINUTES)));
    }

    /**
     * 判断OC是否处于招募状态。
     *
     * @param status OC状态编码
     * @return 状态为招募时返回true
     */
    private boolean isRecruiting(String status) {
        return TornOcStatusEnum.RECRUITING.getCode().equals(status);
    }

    /**
     * 判断OC状态是否为标题支持的状态。
     *
     * @param status OC状态编码
     * @return 状态受支持时返回true
     */
    private boolean isSupportedStatus(String status) {
        return isRecruiting(status) || TornOcStatusEnum.PLANNING.getCode().equals(status);
    }

    /**
     * 标题时间受控描述。
     *
     * @param text       时间文案，没有文案时为空字符串
     * @param timeStatus 时间状态
     */
    public record Description(
            String text,
            OcImageTimeStatusEnum timeStatus) {
    }
}
