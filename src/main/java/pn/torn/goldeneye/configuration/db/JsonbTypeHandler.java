package pn.torn.goldeneye.configuration.db;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * PostgreSQL JSONB列通用类型处理器
 * <p>
 * DO字段以Java {@link String}承载JSON文本,但Liquibase已将对应数据库列声明为JSONB类型。
 * MyBatis-Plus默认以普通字符串绑定参数,写入JSONB列时PostgreSQL会因类型不匹配报错。
 * 本处理器在设置参数时将字符串包装为PGobject(type=jsonb),读取时直接返回字符串,
 * 使{@code BaseMapper.insert/update}与自定义XML均能正确读写JSONB列。
 *
 * <h3>使用方式</h3>
 * <p>
 * 在DO的JSONB字段上添加注解,并确保DO的{@code @TableName}开启{@code autoResultMap = true}:
 * <pre>
 * &#64;TableName(value = "t_xxx", autoResultMap = true)
 * public class XxxDO extends BaseDO {
 *     &#64;TableField(typeHandler = JsonbTypeHandler.class)
 *     private String featureSnapshot;
 * }
 * </pre>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.27
 */
@MappedJdbcTypes(JdbcType.OTHER)
@MappedTypes(String.class)
public class JsonbTypeHandler extends BaseTypeHandler<String> {

    /**
     * PostgreSQL JSONB类型标识
     */
    private static final String JSONB_TYPE = "jsonb";

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject pgObject = new PGobject();
        pgObject.setType(JSONB_TYPE);
        pgObject.setValue(parameter);
        ps.setObject(i, pgObject);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        Object value = rs.getObject(columnName);
        return value == null ? null : value.toString();
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        Object value = rs.getObject(columnIndex);
        return value == null ? null : value.toString();
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        Object value = cs.getObject(columnIndex);
        return value == null ? null : value.toString();
    }
}
