package pn.torn.goldeneye.torn.model.faction.rw;

import lombok.Data;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalTime;
import java.util.List;

/**
 * 帮派RW详细响应参数
 *
 * @author Bai
 * @version 1.6.4
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

    /**
     * 获取本帮派在本次RW中的参战信息。
     *
     * @param factionId 本帮派ID
     * @return 本帮派参战信息
     * @throws BizException 参战帮派列表中不含本帮派时抛出
     */
    public TornFactionRwFactionVO getSelfFaction(long factionId) {
        return factions.stream()
                .filter(f -> f.getId() == factionId)
                .findAny()
                .orElseThrow(() -> new BizException("RW帮派解析错误"));
    }

    /**
     * 获取对手帮派在本次RW中的参战信息。
     *
     * @param factionId 本帮派ID
     * @return 对手帮派参战信息
     * @throws BizException 参战帮派列表中不含对手帮派时抛出
     */
    public TornFactionRwFactionVO getOpponentFaction(long factionId) {
        TornFactionRwFactionVO opponentFaction = factions.stream()
                .filter(f -> f.getId() != factionId)
                .findAny().orElse(null);
        if (opponentFaction == null) {
            throw new BizException("RW帮派解析错误");
        }

        return opponentFaction;
    }

    /**
     * 生成帮派名称的默认简称。
     *
     * <p>按空格与连字符分段后取各段首字母大写拼接，例如 Destructive Anomaly 得到 DA、
     * The Next Level - Forge 得到 TNLF。该结果只是兜底默认值，非首字母规则的自然简称
     * （如 PTA、MHY）由人工改库修正。</p>
     *
     * @param factionName 帮派名称
     * @return 默认简称；名称为空时返回null
     */
    public static String defaultShortName(String factionName) {
        if (factionName == null || factionName.isBlank()) {
            return null;
        }

        String[] segments = factionName.trim().split("[\\s-]+");
        StringBuilder shortName = new StringBuilder(segments.length);
        for (String segment : segments) {
            if (!segment.isEmpty()) {
                shortName.append(Character.toUpperCase(segment.charAt(0)));
            }
        }

        return shortName.toString();
    }

    /**
     * 将API响应转换为RW持久化对象。
     *
     * <p>只写入登记当时已经确定的字段：目标分数大于0才写入，胜方未产生时留null；
     * 我方与对手的最终分数一律不写，保持“null=未知”语义，避免登记时写0污染回填的幂等判断。</p>
     *
     * @param factionId 本帮派ID
     * @return RW持久化对象
     * @throws BizException 参战帮派解析失败时抛出
     */
    public TornFactionRwDO convert2DO(long factionId) {
        TornFactionRwDO rw = new TornFactionRwDO();
        rw.setId(this.id);
        rw.setFactionId(factionId);

        TornFactionRwFactionVO faction = getSelfFaction(factionId);
        TornFactionRwFactionVO opponentFaction = getOpponentFaction(factionId);

        rw.setFactionName(faction.getName());
        rw.setOpponentFactionId(opponentFaction.getId());
        rw.setOpponentFactionName(opponentFaction.getName());
        rw.setOpponentShortName(defaultShortName(opponentFaction.getName()));
        rw.setStartTime(DateTimeUtils.convertToDateTime(this.start));
        rw.setEndTime(this.end == null || this.end == 0L ? null : DateTimeUtils.convertToDateTime(this.end));
        rw.setGatheringTime(LocalTime.of(8, 0, 0));
        rw.setDisbandTime(LocalTime.of(0, 0, 0));
        rw.setTargetScore(this.target > 0 ? this.target : null);
        rw.setWinnerFactionId(this.winner != 0L ? this.winner : null);
        return rw;
    }
}