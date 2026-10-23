# API Key 加密存储技术方案

## 1. 文档信息

- 目标版本：1.9.3
- 风险等级：L3（凭据安全、存量数据迁移、影响所有 Torn 调用的取 Key 路径）
- 影响范围：`torn_api_key` 表、Key 读取链路、启动流程
- 前置依赖：无（独立于对外接口改造，先行实施）

## 2. 背景与已核验事实

1. `torn_api_key.api_key` 当前为明文 `VARCHAR(32)`（`db/changelog/0.1.0/setting.yaml`）。
2. 业务读取点仅 5 处、分布在 3 个类：`TornApiImpl`（2）、`TornKeyDataService`（2）、`BindKeyStrategyImpl`（1）。
3. 写入入口仅 2 个：`TornApiKeyConfig#addApiKey`、`TornApiKeyConfig#updateApiKey`。
4. `TornApiKeyDO` 已标注 `autoResultMap = true`，具备挂载 TypeHandler 的条件。
5. 日志现状：报错日志只输出 `uri` 与 `Key ID`，未输出 Key 本体。

## 3. 目标与非目标

**目标**：数据库静态存储不再出现明文 Key；读写业务代码零改动；存量数据一次性迁移且可重复执行。

**非目标**：传输链路加密（由 nginx 反代终止 TLS）、PostgreSQL 连接 TLS（数据库仅在 docker compose 内网可达）、KMS/Vault 托管、密钥自动轮换编排、字段级权限控制。

## 4. 设计

### 4.1 存储格式

```
v1:<Base64( iv(12B) || cipherText || tag(16B) )>
```

- 前缀 `v1` 是格式版本号。以后更换算法或密钥结构时新增 `v2`，读取端按前缀分派，老数据无需停机重写。
- MySQL/PostgreSQL 均为 `VARCHAR`，ASCII 前缀便于直接判定"是否为密文"，迁移任务靠它实现幂等。
- 16 字符 Key 加密后约 63 字符，列宽需从 32 扩到 128，为轮换与版本前缀留余量。

### 4.2 组件职责

| 组件 | 职责 |
| --- | --- |
| `TornApiKeyCipher` | AES-256-GCM 加解密；解析版本前缀；密钥缺失时构造失败 |
| `TornApiKeyTypeHandler` | MyBatis 边界上的透明加解密，业务代码不可见 |
| `TornApiKeyMigrationService` | 一次性把存量明文改写为密文，幂等 |
| `KeyCipherProperty` | 读取 `security.key-cipher` 配置：密钥、迁移开关 |

### 4.3 密钥管理

- 密钥来自环境变量（`SECURITY_KEY_CIPHER_SECRET`，Base64 的 32 字节），由 `KeyCipherProperty` 绑定。
- 启动时校验长度与 Base64 合法性；不合法直接抛出并终止启动。宁可不启动，也不允许以明文形态运行。
- 密钥不得写入 `application.yml` 默认值、不得入库、不得进日志。
- `TornApiKeyCipher` 为单例 Bean，密钥只在构造期读取一次，运行期不重新读取文件。

### 4.4 透明加解密

在 `TornApiKeyDO#apiKey` 上挂 `@TableField(value = "api_key", typeHandler = TornApiKeyTypeHandler.class)`。

**实施前必须先做 30 分钟验证**：当前 MyBatis-Plus 版本的 `typeHandler` 是否对 `insert`/`updateById` 同时生效。

- 若生效：业务代码零改动。
- 若不生效（只对查询结果映射生效）：在 `TornApiKeyDAO` 覆写 `save`、`updateById`、`lambdaUpdate` 的落库口径，统一在写库前加密。只改这一个 DAO，禁止在业务类里散落加解密调用。

### 4.5 存量迁移

- 触发：`ApplicationRunner`，受 `security.key-cipher.migration-enabled` 开关控制（默认 `false`）。
- 流程：查全表 → 过滤已带 `v1:` 前缀的行 → 逐行加密回写 → 统计日志（扫描数/改写数/跳过数）。
- 幂等：以前缀判定，重复执行结果不变。
- 分批：每批 200 行，避免长事务；不使用全表 update。
- 失败：单行失败记录 Key ID 并继续，结束后汇总；不静默吞掉。
- 迁移完成后，同一开关保持 `true` 也无副作用（全部跳过）。

### 4.6 失效与降级

解密失败（密钥更换、数据损坏）**不允许降级为明文使用**：标记该 Key 失效（复用 `TornApiKeyConfig#invalidateKey`）、记录 `error` 日志（只打 Key ID），向上抛出业务异常。由使用者重新绑定。

## 5. 包与文件规划

### 5.1 新增：`pn.torn.goldeneye.configuration.crypto`

| 文件 | 说明 |
| --- | --- |
| `TornApiKeyCipher.java` | `encrypt(String)` / `decrypt(String)` / `isEncrypted(String)`；私有构造校验密钥；GCM 随机 IV |
| `TornApiKeyTypeHandler.java` | 继承 `BaseTypeHandler<String>`；set 加密、get 解密；空值与已密文（前缀命中）直接返回 |
| `TornApiKeyMigrationRunner.java` | 仅做开关判断与调用迁移服务，不含业务逻辑 |

### 5.2 新增：`pn.torn.goldeneye.configuration.property`

| 文件 | 说明 |
| --- | --- |
| `KeyCipherProperty.java` | `@ConfigurationProperties(prefix = "security.key-cipher")`；字段 `secret`、`migrationEnabled` |

### 5.3 新增：`pn.torn.goldeneye.torn.service.data`

| 文件 | 说明 |
| --- | --- |
| `TornApiKeyMigrationService.java` | 分批扫描、前缀过滤、回写、统计 |

### 5.4 修改

| 文件 | 改动 |
| --- | --- |
| `repository/model/setting/TornApiKeyDO.java` | `apiKey` 增加 `typeHandler`；类头版本号同步 |
| `repository/dao/setting/TornApiKeyDAO.java` | 仅当 4.4 验证失败时，覆写三个写方法 |
| `src/main/resources/application.yml` | 新增 `security.key-cipher` 段：`secret: ${SECURITY_KEY_CIPHER_SECRET:}`、`migration-enabled: false` |
| `db/changelog/db.changelog-master.yaml` | 追加 1.9.3 的 include |
| `TornApiKeyConfig.java` | 不加解密逻辑；仅在失效分支补一条 decrypt 失败的日志路径（如已被 4.6 覆盖则不新增方法） |

### 5.5 数据库变更：`db/changelog/1.0.1-2.0.0/1.9.3/api-key-cipher.yaml`

- `modifyDataType`：`torn_api_key.api_key` → `VARCHAR(128)`。
- 不新增列、不改索引；不用 SQL 做密文迁移（应用层密钥不可用于 SQL）。

## 6. 关键契约

```java
public interface KeyCipher {
    /** 加密明文，返回值带格式版本前缀。 */
    String encrypt(String plain);

    /** 解密；入参不带前缀时按明文原样返回，兼容迁移未完成的行。 */
    String decrypt(String stored);

    boolean isEncrypted(String stored);
}
```

用接口 `KeyCipher` 而非直接依赖实现类：后续换算法或加 KMS 时只新增实现，TypeHandler 与迁移服务不改。

## 7. 测试方案

只新增 1 个测试类 `TornApiKeyCipherTest`，覆盖：

1. 加解密往返（含 16 字符真实 Key 形态）；
2. 同一明文两次加密结果不同（IV 随机）；
3. `isEncrypted` 对明文/密文判定；
4. 密钥长度不合法时构造抛异常。

迁移幂等不写单测，用真实库回放验证一次（执行两次，第二次改写数为 0）——这类行为用 mock 验证没有意义。

不新增：Getter/Setter 测试、`TornApiKeyDO` 字段赋值测试、`TornApiKeyConfig` 既有链路的重复测试。

## 8. 风险与对策

| 风险 | 对策 |
| --- | --- |
| TypeHandler 对写路径不生效，导致明文落库 | 实施前 30 分钟验证；不生效则在 DAO 单点兜底，并补一条"写库后直连查库断言为密文"的回放 |
| 生产密钥配置缺失导致启动失败 | 发布单中列为必填项；启动失败日志明确写出变量名 |
| 历史备份文件中仍有明文 | 备份加密与恢复演练列入运维清单；本期不回溯历史备份 |
| 迁移中途失败留下半表密文 | 前缀判定保证可重复执行；失败行有 Key ID 清单 |

## 9. 明确不做

pgcrypto、列/行级权限、KMS、自动轮换、内存中密钥的额外保护（运行时明文不可避免）。

## 10. 完成条件

1. 库中 `api_key` 无一行明文（`SELECT` 抽查 + 迁移日志改写数）；
2. 全量 Torn 调用正常（`spiderKey` 日报任务跑通一次）；
3. 缺密钥时启动失败且日志可定位；
4. 日志与接口响应中不出现 Key 片段；
5. `TornApiKeyCipherTest` 通过。
