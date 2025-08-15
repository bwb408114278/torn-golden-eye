package pn.torn.goldeneye.repository.mapper.faction.oc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;

import java.util.List;

/**
 * Torn Oc 数据库访问层
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.29
 */
@Mapper
public interface TornFactionOcMapper extends BaseMapper<TornFactionOcDO> {
    /**
     * 通过ID列表删除
     *
     * @param idList ID列表
     */
    void deleteByIdList(@Param("list") List<Long> idList);
}