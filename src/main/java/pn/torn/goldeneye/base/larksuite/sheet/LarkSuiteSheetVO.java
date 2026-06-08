package pn.torn.goldeneye.base.larksuite.sheet;

import com.lark.oapi.core.response.BaseResponse;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 操作工作表响应参数
 *
 * @author Bai
 * @version 1.2.0
 * @since 2026.06.05
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class LarkSuiteSheetVO extends BaseResponse<List<LarkSuiteSheetReplyVO>> {
    /**
     * 回复消息列表
     */
    private List<LarkSuiteSheetReplyVO> replies;
}