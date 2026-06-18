# 数据库查询规范

## 元信息
- 文档类型：数据库查询规范 Skill
- 适用项目：Golden-Eye
- 适用版本：1.2.0及以上
- 最后更新：2026.06.17
- 维护人：Bai
- 状态：有效

---

## 摘要

本文说明 AI Agent 查询 PostgreSQL 数据库时的规则和常用 SQL。

## 必须遵守

- 本地数据库已经同步生产数据，可以直接当做生产数据使用
- 默认只能执行 SELECT。
- 禁止执行 INSERT、UPDATE、DELETE、DROP、TRUNCATE、ALTER。
- 禁止查询或输出敏感字段明文。
- 查询样例数据时必须加 LIMIT，如果任务执行过程中需要大量样本数据分析需要经过同意或用户已经指出需要大量数据
- 修改数据表结构时，必须先提问修改方案是否可行
  - 每个表和字段必须有中文注释
  - 必须使用Liquibase的yaml格式修改

## 查询所有表

```postgresql
SELECT table_schema, table_name
FROM information_schema.tables
WHERE table_schema NOT IN ('pg_catalog', 'information_schema')
ORDER BY table_schema, table_name;
```

## 查询表结构

```postgresql
SELECT column_name,
       data_type,
       is_nullable,
       column_default
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'your_table'
ORDER BY ordinal_position;
```

## 查询索引

```postgresql
SELECT indexname,
       indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename = 'your_table';
```

## 查询外键

```postgresql
SELECT tc.constraint_name,
       kcu.column_name,
       ccu.table_name  AS foreign_table_name,
       ccu.column_name AS foreign_column_name
FROM information_schema.table_constraints AS tc
         JOIN information_schema.key_column_usage AS kcu
              ON tc.constraint_name = kcu.constraint_name
         JOIN information_schema.constraint_column_usage AS ccu
              ON ccu.constraint_name = tc.constraint_name
WHERE tc.constraint_type = 'FOREIGN KEY'
  AND tc.table_name = 'your_table';
```

## 查询少量样例数据

```postgresql
SELECT *
FROM your_table
LIMIT 5;
```