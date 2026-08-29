package pn.torn.goldeneye.repository.mapper.setting;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;

import java.util.List;

/**
 * Torn设置OC岗位数据库访问层
 *
 * @author Bai
 * @version 1.5.1
 * @since 2025.08.21
 */
@Mapper
public interface TornSettingOcSlotMapper extends BaseMapper<TornSettingOcSlotDO> {
    /**
     * 批量插入缺失的OC岗位目录。
     *
     * <p>仅做INSERT：不写入id（由数据库自增主键生成），不使用ON CONFLICT；
     * 并发重复写入由TornSettingOcSyncManager的JVM共享锁收敛。</p>
     *
     * @param list 待插入的岗位目录列表，不能为空列表
     * @return 实际插入行数
     */
    int insertMissingBatch(@Param("list") List<TornSettingOcSlotDO> list);
}