package pn.torn.goldeneye.base.larksuite.sheet;

import lombok.Data;

/**
 * 操作工作表属性响应参数
 *
 * @author Bai
 * @version 1.2.0
 * @since 2026.06.05
 */
@Data
public class LarkSuiteSheetPropertyVO {
    /**
     * 表格ID
     */
    private String sheetId;
    /**
     * 标题
     */
    private String title;
    /**
     * 序号
     */
    private int index;
}