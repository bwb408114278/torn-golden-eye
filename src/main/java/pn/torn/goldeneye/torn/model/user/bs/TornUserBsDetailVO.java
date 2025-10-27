package pn.torn.goldeneye.torn.model.user.bs;

import lombok.Data;
import pn.torn.goldeneye.repository.model.user.TornUserBsSnapshotDO;

import java.time.LocalDate;

/**
 * Torn用户BS详情响应参数
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.10.27
 */
@Data
public class TornUserBsDetailVO {
    /**
     * 力量
     */
    private TornUserBsValueVO strength;
    /**
     * 防御
     */
    private TornUserBsValueVO defense;
    /**
     * 速度
     */
    private TornUserBsValueVO speed;
    /**
     * 敏捷
     */
    private TornUserBsValueVO dexterity;
    /**
     * 总值
     */
    private long total;

    public TornUserBsSnapshotDO convert2DO(long userId, LocalDate date) {
        TornUserBsSnapshotDO snapshot = new TornUserBsSnapshotDO();
        snapshot.setUserId(userId);
        snapshot.setRecordDate(date);
        snapshot.setTotal(total);
        snapshot.setStrength(strength.getValue());
        snapshot.setDefense(defense.getValue());
        snapshot.setSpeed(speed.getValue());
        snapshot.setDexterity(dexterity.getValue());
        return snapshot;
    }
}