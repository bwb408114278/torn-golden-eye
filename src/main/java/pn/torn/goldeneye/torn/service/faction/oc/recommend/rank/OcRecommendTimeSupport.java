package pn.torn.goldeneye.torn.service.faction.oc.recommend.rank;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcPhaseEnum;

import java.time.LocalDateTime;

/**
 * OC准备阶段判定工具，推荐与分配两个入口共用。
 *
 * <p>判定顺序固定：ready_time为空为新队（无人加入时Torn不返回预计开动时间，是正常状态而不是异常）；
 * ready_time晚于当前时刻为未开动；否则为停转。阶段判断只在本类实现一次，
 * 任何位置不得直接对ready_time做日期运算。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.23
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public final class OcRecommendTimeSupport {
    /**
     * 判断OC当前所处的准备阶段。
     *
     * @param oc  OC数据，可为空
     * @param now 本次推荐的时间基准，不能为空
     * @return OC准备阶段；OC为空或ready_time为空时返回新队
     */
    public static OcPhaseEnum resolvePhase(TornFactionOcDO oc, LocalDateTime now) {
        if (oc == null || oc.getReadyTime() == null) {
            return OcPhaseEnum.NEW_TEAM;
        }
        return oc.getReadyTime().isAfter(now) ? OcPhaseEnum.NOT_STARTED : OcPhaseEnum.PAUSED;
    }

    /**
     * 计算候选池归一化后的准备时间。
     *
     * <p>用户已加入某个OC时，其自身的加入行为已经把该OC的ready_time推出一天，放入候选池前必须扣除，
     * 否则同一岗位会以虚高的准备时间参与比较。新队没有任何人加入，不存在这份偏移，因此不做扣除。</p>
     *
     * @param oc  OC数据
     * @param now 本次推荐的时间基准，不能为空
     * @return 归一化后的准备时间；新队返回null（原值本身为空）
     */
    public static LocalDateTime normalizeReadyTime(TornFactionOcDO oc, LocalDateTime now) {
        if (resolvePhase(oc, now) == OcPhaseEnum.NEW_TEAM) {
            return null;
        }
        return oc.getReadyTime().minusDays(1);
    }
}
