package pn.torn.goldeneye.constants.torn;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * PC赛车常量
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public class RacingConstants {
    /**
     * 赛事名称，匹配时不区分大小写
     */
    public static final String RACE_TITLE = "SMTHPC";
    /**
     * 榜单图片标题前缀
     */
    public static final String RACE_TITLE_PREFIX = "每日PC大赛成绩";
    /**
     * 成绩可抓取的赛事状态
     */
    public static final String RACE_FINISHED_STATUS = "finished";
    /**
     * 赛车列表接口的自定义赛分类值，用于在时间窗内排除官方赛
     */
    public static final String RACE_CATEGORY_CUSTOM = "custom";
    /**
     * 常任创建人的Torn用户ID，其本人参赛，作为定位链的一级候选
     */
    public static final long CREATOR_USER_ID = 2554043L;
    /**
     * 抽奖种子后缀，与赛事ID拼接后作为固定随机种子
     */
    public static final String DRAW_SEED_SUFFIX = "Ciallo";
    /**
     * 每日抓取动态任务ID，执行完毕后自续期
     */
    public static final String CAPTURE_TASK_ID = "pc-race-capture";
    /**
     * 每日抓取时刻的小时（北京时间）
     */
    public static final int CAPTURE_HOUR = 8;
    /**
     * 每日抓取时刻的分钟（北京时间）
     */
    public static final int CAPTURE_MINUTE = 30;
    /**
     * 个人成绩展示的最近场次
     */
    public static final int SCORE_HISTORY_LIMIT = 10;
    /**
     * 定位赛事时列表接口拉取的条数
     */
    public static final int DISCOVERY_PAGE_SIZE = 50;
    /**
     * 单次定位允许扫描的候选Key上限
     */
    public static final int DISCOVERY_SCAN_LIMIT = 60;
}
