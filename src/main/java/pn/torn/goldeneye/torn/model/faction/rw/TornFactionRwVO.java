package pn.torn.goldeneye.torn.model.faction.rw;

import lombok.Data;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.util.List;

/**
 * 帮派RW详细响应参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.25
 */
@Data
public class TornFactionRwVO {
    /**
     * RW ID
     */
    private long id;
    /**
     * 开始时间
     */
    private long start;
    /**
     * 结束时间
     */
    private Long end;
    /**
     * 目标分数
     */
    private int target;
    /**
     * 胜方帮派
     */
    private long winner;
    /**
     * 参战帮派
     */
    private List<TornFactionRwFactionVO> factions;

    public TornFactionRwDO convert2DO(long factionId) {
        TornFactionRwDO rw = new TornFactionRwDO();
        rw.setId(this.id);
        rw.setFactionId(factionId);

        TornFactionRwFactionVO faction = factions.stream()
                .filter(f -> f.getId() == factionId)
                .findAny().orElse(null);
        TornFactionRwFactionVO opponentFaction = factions.stream()
                .filter(f -> f.getId() != factionId)
                .findAny().orElse(null);
        if (faction == null || opponentFaction == null) {
            throw new BizException("RW帮派解析错误");
        }

        rw.setFactionName(faction.getName());
        rw.setOpponentFactionId(opponentFaction.getId());
        rw.setOpponentFactionName(opponentFaction.getName());
        rw.setStartTime(DateTimeUtils.convertToDateTime(this.start));
        rw.setEndTime(this.end == null || this.end == 0L ? null : DateTimeUtils.convertToDateTime(this.end));
        return rw;
    }
}