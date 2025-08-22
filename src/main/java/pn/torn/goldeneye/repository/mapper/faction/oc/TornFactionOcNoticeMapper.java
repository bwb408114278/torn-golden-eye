package pn.torn.goldeneye.repository.mapper.faction.oc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcNoticeDO;

/**
 * Torn Oc跳过表数据库访问层
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.30
 */
@Mapper
public interface TornFactionOcNoticeMapper extends BaseMapper<TornFactionOcNoticeDO> {
}