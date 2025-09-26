package pn.torn.goldeneye.torn.model.torn.bank;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Torn银行响应参数
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.26
 */
@Data
public class TornBankVO {
    /**
     * 状态详情, Key为状态Key
     */
    private Map<String, BigDecimal> bank;
}