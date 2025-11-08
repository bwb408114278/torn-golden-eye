package pn.torn.goldeneye.repository.mapper.faction.oc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeDO;

/**
 * OC收益分配记录数据库访问层
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.03
 */
@Mapper
public interface TornFactionOcIncomeMapper extends BaseMapper<TornFactionOcIncomeDO> {
}