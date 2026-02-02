package pn.torn.goldeneye.torn.model.user.log;

import lombok.Data;

import java.util.List;

/**
 * Torn用户日志响应参数
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.30
 */
@Data
public class TornUserLogVO {
    /**
     * 日志列表
     */
    private List<TornUserLogListVO> log;
}