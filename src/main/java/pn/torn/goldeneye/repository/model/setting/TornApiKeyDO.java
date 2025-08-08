package pn.torn.goldeneye.repository.model.setting;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDate;

/**
 * Torn Api Key表
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.07
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_api_key", autoResultMap = true)
public class TornApiKeyDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * Api Key
     */
    private String apiKey;
    /**
     * Key级别
     */
    private String keyLevel;
    /**
     * 使用次数
     */
    private Integer useCount;
    /**
     * 使用日期
     */
    private LocalDate useDate;

    public TornApiKeyDO copyNewData() {
        TornApiKeyDO key = new TornApiKeyDO();
        key.setUserId(this.userId);
        key.setApiKey(this.apiKey);
        key.setKeyLevel(this.keyLevel);
        key.setUseCount(0);
        key.setUseDate(LocalDate.now());
        return key;
    }
}