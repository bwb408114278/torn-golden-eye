package pn.torn.goldeneye.base.larksuite.sheet;

import lombok.Data;

import java.util.List;

/**
 * 操作工作表数据响应参数
 *
 * @author Bai
 * @version 1.2.0
 * @since 2026.06.11
 */
@Data
public class LarkSuiteSheetDataVO {
    /**
     * 回复消息列表
     */
    private List<LarkSuiteSheetReplyVO> replies;
}