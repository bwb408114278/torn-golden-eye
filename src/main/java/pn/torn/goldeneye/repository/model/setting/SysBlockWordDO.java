package pn.torn.goldeneye.repository.model.setting;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.repository.model.BaseDO;

/**
 * 屏蔽词设置表
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.25
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "sys_block_word", autoResultMap = true)
@NoArgsConstructor
public class SysBlockWordDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 屏蔽词
     */
    private String word;
    /**
     * 替换词
     */
    private String replaceWord;
    /**
     * 白名单词汇
     */
    private String whiteList;
}
