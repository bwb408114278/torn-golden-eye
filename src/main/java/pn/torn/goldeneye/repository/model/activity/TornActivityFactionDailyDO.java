package pn.torn.goldeneye.repository.model.activity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDate;

/**
 * Torn活跃度V3帮派日终压缩归档表
 * <p>
 * 一个帮派一个自然日最多一行，保留96字节active/idle/member槽值与96位observed Bitmap，
 * 不依赖当前成员Set；业务唯一键为(faction_id, activity_date)。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.08.28
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_activity_faction_daily", autoResultMap = true)
public class TornActivityFactionDailyDO extends BaseDO {
    /**
     * 主键ID（应用层雪花ID）
     */
    private Long id;
    /**
     * Torn帮派ID
     */
    private Long factionId;
    /**
     * 活动自然日（Asia/Shanghai时区）
     */
    private LocalDate activityDate;
    /**
     * 96位成功采样标记Bitmap（MSB-first位序，每15分钟一槽）
     */
    private byte[] observedBitmap;
    /**
     * 96字节有效活跃人数槽值（每槽无符号字节）
     */
    private byte[] activeCounts;
    /**
     * 96字节idle-only人数槽值（每槽无符号字节）
     */
    private byte[] idleCounts;
    /**
     * 96字节有效成员数槽值（每槽无符号字节）
     */
    private byte[] memberCounts;
    /**
     * 数据版本，固定V3
     */
    private String dataVersion;
}
