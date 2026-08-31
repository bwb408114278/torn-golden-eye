package pn.torn.goldeneye.torn.service.faction.oc.image;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.model.faction.oc.image.OcImageSlotStatusEnum;

import java.math.BigDecimal;

/**
 * OC 表格图片槽位状态解析服务。
 * <p>
 * 解析单个 OC 槽位的唯一 Emoji 状态，统一封装“空转 > 缺少道具 > 准备完成 > 准备中”的优先级，
 * 并处理空槽、未知快照和非法进度降级。
 *
 * @author Bai
 * @version 1.5.2
 * @since 2026.08.30
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OcImageStatusResolver {

    /**
     * 解析槽位状态。
     *
     * @param userId                槽位用户 ID；为 null 表示空槽
     * @param progress              当前成员准备进度；为 null、小于 0 或大于 100 时降级为未知
     * @param requiredItemAvailable 本地道具快照是否可用；只有显式 false 才视为缺少道具
     * @return 唯一槽位状态；空槽和无法确认的状态不会返回 Emoji
     */
    public OcImageSlotStatusEnum resolve(Long userId, BigDecimal progress, Boolean requiredItemAvailable) {
        if (userId == null) {
            return OcImageSlotStatusEnum.EMPTY;
        }

        if (isZero(progress)) {
            return OcImageSlotStatusEnum.IDLE;
        }

        if (Boolean.FALSE.equals(requiredItemAvailable)) {
            return OcImageSlotStatusEnum.MISSING_ITEM;
        }

        if (isFull(progress)) {
            return OcImageSlotStatusEnum.READY;
        }

        if (isPreparing(progress)) {
            return OcImageSlotStatusEnum.PREPARING;
        }

        log.warn("OC槽位状态无法确定，userId={}, progress={}, requiredItemAvailable={}",
                userId, progress, requiredItemAvailable);
        return OcImageSlotStatusEnum.UNKNOWN;
    }

    private boolean isZero(BigDecimal progress) {
        return progress != null && progress.compareTo(BigDecimal.ZERO) == 0;
    }

    private boolean isFull(BigDecimal progress) {
        return progress != null && progress.compareTo(BigDecimal.valueOf(100)) == 0;
    }

    private boolean isPreparing(BigDecimal progress) {
        return progress != null
                && progress.compareTo(BigDecimal.ZERO) > 0
                && progress.compareTo(BigDecimal.valueOf(100)) < 0;
    }
}
