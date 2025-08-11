package pn.torn.goldeneye.base.model;

import lombok.Getter;
import lombok.ToString;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.util.List;

/**
 * 表格数据逻辑模型
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.11
 */
@Getter
@ToString
public class TableDataBO {
    /**
     * 表格数据
     */
    private final List<List<String>> tableData;
    /**
     * 表格配置
     */
    private final TableImageUtils.TableConfig tableConfig;

    public TableDataBO(List<List<String>> tableData) {
        this(tableData, new TableImageUtils.TableConfig());
    }

    public TableDataBO(List<List<String>> tableData, TableImageUtils.TableConfig tableConfig) {
        this.tableData = tableData;
        this.tableConfig = tableConfig;
    }
}