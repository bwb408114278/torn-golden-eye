package pn.torn.goldeneye.torn.model.user.racing;

import lombok.Data;

import java.util.List;

/**
 * Torn赛车单场信息响应参数
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.05.11
 */
@Data
public class TornRaceDetailVO {
    /**
     * 比赛ID
     */
    private Long id;
    /**
     * 比赛名称
     */
    private String title;
    /**
     * 比赛状态
     */
    private String status;
    /**
     * 比赛日程安排
     */
    private TornRaceScheduleVO schedule;
    /**
     * 全量参赛成绩，列表接口即返回，无需再调详情接口
     */
    private List<TornRaceResultVO> results;
}