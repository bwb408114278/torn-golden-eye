package pn.torn.goldeneye.torn.model.torn.bank;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Torn银行定期存款利率明细
 *
 * @author Bai
 * @version 1.3.2
 * @since 2026.08.19
 */
@Data
public class TornBankRateVO {
    /**
     * 存款期限，单位为天
     */
    private Integer days;
    /**
     * 存款利率
     */
    private BigDecimal rate;
}
