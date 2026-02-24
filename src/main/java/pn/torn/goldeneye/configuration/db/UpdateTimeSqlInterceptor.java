package pn.torn.goldeneye.configuration.db;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.sql.Connection;

/**
 * Sql拦截器，在Prepare阶段直接改写Sql字符串，手动更新update_time
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.24
 */
@Intercepts({
        @Signature(type = StatementHandler.class, method = "prepare",
                args = {Connection.class, Integer.class})
})
@Component
public class UpdateTimeSqlInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler handler = (StatementHandler) invocation.getTarget();
        MetaObject metaObject = SystemMetaObject.forObject(handler);

        MappedStatement ms = (MappedStatement) metaObject.getValue("delegate.mappedStatement");
        if (SqlCommandType.UPDATE != ms.getSqlCommandType()) {
            return invocation.proceed();
        }

        BoundSql boundSql = handler.getBoundSql();
        String sql = boundSql.getSql();

        // 已经包含 update_time 则跳过（避免重复注入）
        if (sql.toLowerCase().contains("update_time")) {
            return invocation.proceed();
        }

        String newSql = injectUpdateTime(sql);
        Field sqlField = BoundSql.class.getDeclaredField("sql");
        sqlField.setAccessible(true);
        sqlField.set(boundSql, newSql);

        return invocation.proceed();
    }

    private String injectUpdateTime(String sql) {
        int wherePos = findTopLevelWherePosition(sql);
        String injection = ", update_time = NOW()";

        if (wherePos >= 0) {
            return sql.substring(0, wherePos).stripTrailing() + injection + " " + sql.substring(wherePos);
        }
        return sql.stripTrailing() + injection;
    }

    /**
     * 找到顶层 WHERE 的位置，忽略子查询括号内的 WHERE
     */
    private int findTopLevelWherePosition(String sql) {
        int depth = 0;
        boolean inQuote = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            if (inQuote) {
                // 处理单引号转义 ''
                if (c == '\'' && (i + 1 >= sql.length() || sql.charAt(i + 1) != '\'')) {
                    inQuote = false;
                }
                continue;
            }

            if (c == '\'') {
                inQuote = true;
                continue;
            }
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                depth--;
                continue;
            }

            if (depth == 0 && i + 5 <= sql.length()) {
                String token = sql.substring(i, i + 5).toUpperCase();
                if ("WHERE".equals(token)) {
                    boolean validBefore = i == 0 || !Character.isLetterOrDigit(sql.charAt(i - 1));
                    boolean validAfter = i + 5 >= sql.length() || !Character.isLetterOrDigit(sql.charAt(i + 5));
                    if (validBefore && validAfter) return i;
                }
            }
        }
        return -1;
    }
}