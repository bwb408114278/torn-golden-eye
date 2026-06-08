package pn.torn.goldeneye.base.larksuite.sheet;

import pn.torn.goldeneye.base.larksuite.LarkSuiteManualReqParam;

import java.util.List;
import java.util.Map;

/**
 * 飞书添加工作表请求参数
 *
 * @author Bai
 * @version 1.2.0
 * @since 2026.06.05
 */
public class LarkSuiteAddSheetDTO implements LarkSuiteManualReqParam {
    /**
     * 表格Token
     */
    private final String spreadsheetToken;
    /**
     * 工作表标题
     */
    private final String title;
    /**
     * 序号，添加在何处
     */
    private final int index;

    public LarkSuiteAddSheetDTO(String spreadsheetToken, String title) {
        this(spreadsheetToken, title, 0);
    }

    public LarkSuiteAddSheetDTO(String spreadsheetToken, String title, int index) {
        this.spreadsheetToken = spreadsheetToken;
        this.title = title;
        this.index = index;
    }

    @Override
    public String uri() {
        return "/sheets/v2/spreadsheets/{spreadsheet_token}/sheets_batch_update";
    }

    @Override
    public Map<String, Object> buildUrlParam() {
        return Map.of("spreadsheet_token", spreadsheetToken);
    }

    @Override
    public Map<String, Object> buildBodyParam() {
        return Map.of("requests",
                List.of(Map.of("addSheet",
                        Map.of("properties",
                                Map.of("title", title,
                                        "index", index)
                        )
                ))
        );
    }
}