package pn.torn.goldeneye.repository.mapper.faction.oc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.faction.oc.OcIncomeYieldStatsDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeDO;

import java.util.List;

/**
 * OC收益分配记录数据库访问层
 *
 * @author Bai
 * @version 1.9.3
 * @since 2025.11.03
 */
@Mapper
public interface TornFactionOcIncomeMapper extends BaseMapper<TornFactionOcIncomeDO> {
    /**
     * 查询指定帮派按OC名称与等级聚合的实测产出统计。
     *
     * @param factionId 帮派ID
     * @return 每个（OC名称，等级）的实例数、成功实例数与净收益合计
     */
    List<OcIncomeYieldStatsDO> queryYieldStats(@Param("factionId") long factionId);
}