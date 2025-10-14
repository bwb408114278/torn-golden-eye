package pn.torn.goldeneye.repository.model.setting;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.constants.torn.enums.key.TornKeyTypeEnum;
import pn.torn.goldeneye.repository.model.BaseDO;
import pn.torn.goldeneye.torn.model.key.TornApiKeyInfoVO;

/**
 * Torn Api Key表
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.08.07
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_api_key", autoResultMap = true)
@NoArgsConstructor
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
     * 帮派ID
     */
    private Long factionId;
    /**
     * QQ号
     */
    private Long qqId;
    /**
     * Api Key
     */
    private String apiKey;
    /**
     * Key级别
     */
    private String keyLevel;
    /**
     * 是否有帮派权限
     */
    private Boolean hasFactionAccess;
    /**
     * 使用次数
     */
    private Integer useCount;

    public TornApiKeyDO(long qqId, String apiKey, TornApiKeyInfoVO keyInfo) {
        TornKeyTypeEnum keyType = TornKeyTypeEnum.codeOf(keyInfo.getAccess().getType());
        if (keyType == null) {
            return;
        }

        this.userId = keyInfo.getUser().getId();
        this.factionId = keyInfo.getUser().getFactionId();
        this.qqId = qqId;
        this.apiKey = apiKey;
        this.keyLevel = keyType.getShortCode();
        this.hasFactionAccess = keyInfo.getAccess().getFaction();
        this.useCount = 0;
    }
}