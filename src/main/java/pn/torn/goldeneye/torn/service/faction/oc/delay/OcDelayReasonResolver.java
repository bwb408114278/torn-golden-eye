package pn.torn.goldeneye.torn.service.faction.oc.delay;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.torn.enums.user.TornTravelTargetEnum;
import pn.torn.goldeneye.constants.torn.enums.user.TornUserStatusEnum;
import pn.torn.goldeneye.torn.model.faction.oc.delay.OcDelayReasonEnum;
import pn.torn.goldeneye.torn.model.user.TornUserStatusVO;

import java.util.Optional;

/**
 * OC延误原因判定器。
 * <p>
 * 只按“旅行、住院、监狱、缺道具”的固定优先级判定单次采样中单个成员的原因，
 * 不访问数据库与Torn API，也不做旅行方向文本解析。
 *
 * @author Bai
 * @version 1.6.7
 * @since 2026.10.02
 */
@Slf4j
@Component
public class OcDelayReasonResolver {

    /**
     * 判定成员本次采样是否阻塞及原因。
     * <p>
     * 状态类原因优先级高于缺道具；两者同时命中时以状态为主原因，道具ID作为补充信息返回。
     * 状态为旅行或滞留、海外住院都归为旅行；未定义状态不归类。
     *
     * @param status                成员当前状态；查询不到时为null
     * @param requiredItemId        岗位要求道具ID；无要求时为null
     * @param requiredItemAvailable 成员是否持有该道具
     * @return 命中4类原因时返回原因与补充道具，否则返回空
     */
    public Optional<OcDelayReason> resolve(TornUserStatusVO status, Integer requiredItemId,
                                           Boolean requiredItemAvailable) {
        Integer missingItemId = resolveMissingItemId(requiredItemId, requiredItemAvailable);
        OcDelayReasonEnum statusReason = resolveStatusReason(status);
        if (statusReason != null) {
            return Optional.of(new OcDelayReason(statusReason, missingItemId));
        }
        if (missingItemId != null) {
            return Optional.of(new OcDelayReason(OcDelayReasonEnum.ITEM, missingItemId));
        }

        return Optional.empty();
    }

    /**
     * 按固定优先级判定状态类原因。
     *
     * @param status 成员当前状态；查询不到时为null
     * @return 状态类原因；无命中时返回null
     */
    private OcDelayReasonEnum resolveStatusReason(TornUserStatusVO status) {
        if (status == null) {
            return null;
        }

        String state = status.getState();
        if (isTravelState(state)) {
            return OcDelayReasonEnum.TRAVEL;
        }
        if (TornUserStatusEnum.HOSPITAL.getCode().equals(state)) {
            if (isAbroadHospital(status.getDescription())) {
                return OcDelayReasonEnum.TRAVEL;
            }

            log.debug("OC延误归因判定为住院，状态描述={}", status.getDescription());
            return OcDelayReasonEnum.HOSPITAL;
        }
        if (TornUserStatusEnum.JAIL.getCode().equals(state)) {
            return OcDelayReasonEnum.JAIL;
        }

        return null;
    }

    /**
     * 判断状态是否为旅行类（旅行中或滞留国外）。
     *
     * @param state 状态编码
     * @return 旅行类状态返回true
     */
    private boolean isTravelState(String state) {
        return TornUserStatusEnum.TRAVELING.getCode().equals(state)
                || TornUserStatusEnum.ABROAD.getCode().equals(state);
    }

    /**
     * 判断住院描述是否含旅行目的地，用于把海外住院归为旅行。
     *
     * @param description 住院状态描述
     * @return 描述含任一旅行目的地时返回true
     */
    private boolean isAbroadHospital(String description) {
        return description != null && TornTravelTargetEnum.textContain(description) != null;
    }

    /**
     * 解析同时缺失的道具ID。
     *
     * @param requiredItemId        岗位要求道具ID
     * @param requiredItemAvailable 成员是否持有该道具
     * @return 明确需要且不可用的道具ID；否则返回null
     */
    private Integer resolveMissingItemId(Integer requiredItemId, Boolean requiredItemAvailable) {
        boolean isMissing = requiredItemId != null && Boolean.FALSE.equals(requiredItemAvailable);
        return isMissing ? requiredItemId : null;
    }

    /**
     * 命中结果。
     *
     * @param reason 主原因
     * @param itemId 同时缺失的道具ID；无则为null
     */
    public record OcDelayReason(
            OcDelayReasonEnum reason,
            Integer itemId) {
    }
}
