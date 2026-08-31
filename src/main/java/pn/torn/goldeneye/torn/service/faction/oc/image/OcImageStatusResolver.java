package pn.torn.goldeneye.torn.service.faction.oc.image;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.model.faction.oc.image.OcImageSlotStatusEnum;

import java.math.BigDecimal;

/**
 * OC 表格图片槽位状态解析服务。
 * <p>
 * 解析单个 OC 槽位的唯一 Emoji 状态，统一封装“空转 &gt; 缺少道具 &gt; 准备完成 &gt; 准备中”的优先级，
 * 并处理空槽、未知快照和非法进度降级。
 *
 * @author Bai
 * @version 1.5.2
 * @since 2026.08.30
 */
@Slf4j
@Component
public class OcImageStatusResolver {

    /**
     * 解析槽位状态。
     * <p>
     * 判定顺序：先判空槽；再校验进度有效性，进度为 {@code null}、小于 0 或大于 100 时一律降级为
     * {@link OcImageSlotStatusEnum#UNKNOWN}，即使道具快照为不可用也不得误报缺少道具；
     * 有效进度为 0 时空转优先；有效进度非 0 时，缺少道具必须同时满足
     * {@code requiredItemId != null} 且 {@code requiredItemAvailable} 为显式 {@code false}，
     * 任一条件不满足均不得视为缺少道具；最后按准备完成、准备中返回。
     *
     * @param userId                槽位用户 ID；为 null 表示空槽
     * @param progress              当前成员准备进度；为 null、小于 0 或大于 100 时降级为未知
     * @param requiredItemId        本地道具快照的道具 ID；为 null 表示没有可确认的道具需求
     * @param requiredItemAvailable 本地道具快照是否可用；只有配合非空道具 ID 的显式 false 才视为缺少道具
     * @return 唯一槽位状态；空槽和无法确认的状态不会返回 Emoji
     */
    public OcImageSlotStatusEnum resolve(Long userId, BigDecimal progress,
                                         Integer requiredItemId, Boolean requiredItemAvailable) {
        if (userId == null) {
            return OcImageSlotStatusEnum.EMPTY;
        }

        if (!isValidProgress(progress)) {
            log.warn("OC槽位进度缺失或非法，降级为未知状态，userId={}, progress={}, "
                            + "requiredItemId={}, requiredItemAvailable={}",
                    userId, progress, requiredItemId, requiredItemAvailable);
            return OcImageSlotStatusEnum.UNKNOWN;
        }

        if (isZero(progress)) {
            return OcImageSlotStatusEnum.IDLE;
        }

        if (isMissingItem(requiredItemId, requiredItemAvailable)) {
            return OcImageSlotStatusEnum.MISSING_ITEM;
        }

        return isFull(progress) ? OcImageSlotStatusEnum.READY : OcImageSlotStatusEnum.PREPARING;
    }

    private boolean isValidProgress(BigDecimal progress) {
        return progress != null
                && progress.compareTo(BigDecimal.ZERO) >= 0
                && progress.compareTo(BigDecimal.valueOf(100)) <= 0;
    }

    private boolean isMissingItem(Integer requiredItemId, Boolean requiredItemAvailable) {
        return requiredItemId != null && Boolean.FALSE.equals(requiredItemAvailable);
    }

    private boolean isZero(BigDecimal progress) {
        return progress.compareTo(BigDecimal.ZERO) == 0;
    }

    private boolean isFull(BigDecimal progress) {
        return progress.compareTo(BigDecimal.valueOf(100)) == 0;
    }
}
