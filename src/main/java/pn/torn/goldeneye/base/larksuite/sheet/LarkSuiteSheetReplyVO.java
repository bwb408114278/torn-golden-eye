package pn.torn.goldeneye.base.larksuite.sheet;

import lombok.Data;

/**
 * 操作工作表回复响应参数
 *
 * @author Bai
 * @version 1.2.0
 * @since 2026.06.05
 */
@Data
public class LarkSuiteSheetReplyVO {
    /**
     * 新增工作表响应
     */
    private LarkSuiteAddSheetVO addSheet;
}