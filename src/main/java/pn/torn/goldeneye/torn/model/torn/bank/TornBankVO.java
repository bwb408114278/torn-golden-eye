package pn.torn.goldeneye.torn.model.torn.bank;

import lombok.Data;

import java.util.List;

/**
 * Torn银行响应参数
 *
 * @author Bai
 * @version 1.3.2
 * @since 2025.09.26
 */
@Data
public class TornBankVO {
    /**
     * 银行定期存款利率列表
     */
    private List<TornBankRateVO> bank;
}
