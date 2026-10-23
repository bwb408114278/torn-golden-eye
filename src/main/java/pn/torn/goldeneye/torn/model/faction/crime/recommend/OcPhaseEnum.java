package pn.torn.goldeneye.torn.model.faction.crime.recommend;

/**
 * OC准备阶段枚举。
 *
 * <p>Torn招募中的OC有三种正常状态，必须区分而不是把空值当异常：
 * 无人加入时Torn不返回预计开动时间（新队），有成员加入后ready_time才有值；
 * 有值且晚于当前时刻是未开动，早于或等于当前时刻是停转。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.23
 */
public enum OcPhaseEnum {
    /**
     * 新队：无人加入，ready_time为空，不参与「减一天」归一化
     */
    NEW_TEAM,
    /**
     * 未开动：ready_time晚于当前时刻，仍未到预计开动时间
     */
    NOT_STARTED,
    /**
     * 停转：ready_time早于或等于当前时刻，已过预计开动时间仍在招募
     */
    PAUSED
}
