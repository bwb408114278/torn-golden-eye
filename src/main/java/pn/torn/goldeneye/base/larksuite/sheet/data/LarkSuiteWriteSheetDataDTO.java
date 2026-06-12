package pn.torn.goldeneye.base.larksuite.sheet.data;

import org.springframework.http.HttpMethod;
import pn.torn.goldeneye.base.larksuite.LarkSuiteManualReqParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 飞书写入工作表数据请求参数
 *
 * @author Bai
 * @version 1.2.0
 * @since 2026.06.08
 */
public class LarkSuiteWriteSheetDataDTO implements LarkSuiteManualReqParam {
    /**
     * 表格Token
     */
    private final String spreadsheetToken;
    /**
     * 工作表ID
     */
    private final String sheetId;
    /**
     * 开始的行列
     */
    private final String startRowColumn;
    /**
     * 结束的行列
     */
    private final String endRowColumn;
    /**
     * 值列表
     */
    private final List<List<Object>> values = new ArrayList<>();

    public LarkSuiteWriteSheetDataDTO(String spreadsheetToken, String sheetId, String startRowColumn, String endRowColumn) {
        this.spreadsheetToken = spreadsheetToken;
        this.sheetId = sheetId;
        this.startRowColumn = startRowColumn;
        this.endRowColumn = endRowColumn;
    }

    public void addRow(List<Object> row) {
        this.values.add(row);
    }

    @Override
    public String uri() {
        return "/sheets/v2/spreadsheets/{spreadsheetToken}/values";
    }

    @Override
    public HttpMethod method() {
        return HttpMethod.PUT;
    }

    @Override
    public Map<String, Object> buildUrlParam() {
        return Map.of("spreadsheetToken", spreadsheetToken);
    }

    @Override
    public Map<String, Object> buildBodyParam() {
        return Map.of("valueRange",
                Map.of("range", this.sheetId + "!" + this.startRowColumn + ":" + this.endRowColumn,
                        "values", this.values
                )
        );
    }
}