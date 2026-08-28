package pn.torn.goldeneye.repository.model.activity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDate;

/**
 * Torn活跃度V3用户日终压缩归档表
 * <p>
 * 一个用户一个自然日最多一行，保留96位observed/active/idle Bitmap压缩事实，
 * 不保存"用户×15分钟槽"逐行明细；业务唯一键为(user_id, activity_date)。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.08.28
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_activity_user_daily", autoResultMap = true)
public class TornActivityUserDailyDO extends BaseDO {
    /**
     * 主键ID（应用层雪花ID）
     */
    private Long id;
    /**
     * Torn用户ID
     */
    private Long userId;
    /**
     * 活动自然日（Asia/Shanghai时区）
     */
    private LocalDate activityDate;
    /**
     * 96位observed采样Bitmap（MSB-first位序，每15分钟一槽）
     */
    private byte[] observedBitmap;
    /**
     * 96位有效活跃Bitmap（Online或15分钟内recentAction）
     */
    private byte[] activeBitmap;
    /**
     * 96位idle-only Bitmap（Idle且无近期动作）
     */
    private byte[] idleBitmap;
    /**
     * 数据版本，固定V3
     */
    private String dataVersion;
}
