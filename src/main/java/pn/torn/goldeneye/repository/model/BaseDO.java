package pn.torn.goldeneye.repository.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 基础数据库映射类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@Data
public class BaseDO {
    /**
     * 删除标识
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Integer deleted;
    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime createTime;
    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}