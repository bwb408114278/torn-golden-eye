package pn.torn.goldeneye.torn.service.faction.oc.image;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.model.faction.oc.image.OcImageSlotStatusEnum;

import java.math.BigDecimal;

/**
 * OC图片槽位状态解析器。
 * <p>
 * 只根据槽位本地快照解析唯一状态，不查询数据库或Torn API。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
@Component
public class OcImageStatusResolver {

    /**
     * 按空槽、进度合法性、空转、缺道具、完成、准备中的顺序解析状态。
     *
     * @param userId                槽位用户ID；为空表示空槽
     * @param progress              成员准备进度
     * @param requiredItemId        需求道具ID
     * @param requiredItemAvailable 需求道具可用性
     * @return 唯一槽位状态
     */
    public OcImageSlotStatusEnum resolve(Long userId, BigDecimal progress,
                                         Integer requiredItemId, Boolean requiredItemAvailable) {
        if (userId == null) {
            return OcImageSlotStatusEnum.EMPTY;
        }
        if (!isValidProgress(progress)) {
            return OcImageSlotStatusEnum.UNKNOWN;
        }
        if (progress.compareTo(BigDecimal.ZERO) == 0) {
            return OcImageSlotStatusEnum.IDLE;
        }
        if (requiredItemId != null && Boolean.FALSE.equals(requiredItemAvailable)) {
            return OcImageSlotStatusEnum.MISSING_ITEM;
        }
        if (progress.compareTo(BigDecimal.valueOf(100)) == 0) {
            return OcImageSlotStatusEnum.READY;
        }
        return OcImageSlotStatusEnum.PREPARING;
    }

    private boolean isValidProgress(BigDecimal progress) {
        return progress != null
                && progress.compareTo(BigDecimal.ZERO) >= 0
                && progress.compareTo(BigDecimal.valueOf(100)) <= 0;
    }
}
