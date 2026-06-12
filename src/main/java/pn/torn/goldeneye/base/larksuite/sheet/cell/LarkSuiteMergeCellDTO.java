package pn.torn.goldeneye.base.larksuite.sheet.cell;

import pn.torn.goldeneye.base.larksuite.LarkSuiteManualReqParam;

import java.util.Map;

/**
 * 飞书合并单元格参数
 *
 * @author Bai
 * @version 1.2.0
 * @since 2026.06.11
 */
public class LarkSuiteMergeCellDTO implements LarkSuiteManualReqParam {
    /**
     * 表格Token
     */
    private final String spreadsheetToken;

    /**
     * 工作表ID
     */
    private final String sheetId;

    /**
     * 单元格范围
     */
    private final String cellRange;

    public LarkSuiteMergeCellDTO(String spreadsheetToken, String sheetId, String cellRange) {
        this.spreadsheetToken = spreadsheetToken;
        this.sheetId = sheetId;
        this.cellRange = cellRange;
    }

    @Override
    public String uri() {
        return "/sheets/v2/spreadsheets/{spreadsheetToken}/merge_cells";
    }

    @Override
    public Map<String, Object> buildUrlParam() {
        return Map.of("spreadsheetToken", spreadsheetToken);
    }

    @Override
    public Map<String, Object> buildBodyParam() {
        return Map.of(
                "range", sheetId + "!" + cellRange,
                "mergeType", "MERGE_ALL"
        );
    }
}