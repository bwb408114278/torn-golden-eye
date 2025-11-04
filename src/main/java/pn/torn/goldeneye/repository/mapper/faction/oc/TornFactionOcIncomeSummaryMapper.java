package pn.torn.goldeneye.repository.mapper.faction.oc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeSummaryDO;

/**
 * OC收益汇总数据库访问层
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.04
 */
@Mapper
public interface TornFactionOcIncomeSummaryMapper extends BaseMapper<TornFactionOcIncomeSummaryDO> {
}