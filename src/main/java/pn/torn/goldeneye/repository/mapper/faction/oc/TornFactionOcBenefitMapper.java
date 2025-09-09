package pn.torn.goldeneye.repository.mapper.faction.oc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitDO;

/**
 * OC收益数据库访问层
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.09.09
 */
@Mapper
public interface TornFactionOcBenefitMapper extends BaseMapper<TornFactionOcBenefitDO> {
}