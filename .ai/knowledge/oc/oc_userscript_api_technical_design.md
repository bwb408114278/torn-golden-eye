# 金眼对外 HTTP 接口与 Torn Key 鉴权技术方案

## 1. 文档信息

- 目标版本：1.9.3（在 Key 加密方案之后实施）
- 风险等级：L3（新增对外入口、凭据校验、外部写入）
- 影响范围：新增 `web` 包、观测表、`BindKeyStrategyImpl`（委托改造）、应用配置
- 配套文档：`api_key_cipher_technical_design.md`、`oc_userscript_client_technical_design.md`

## 2. 目标与非目标

**目标**：为金眼提供第一套对外 HTTP 能力，用用户自己的 Torn Key 完成身份与帮派校验，支撑油猴插件读取 OC 推荐、上报空位成功率；契约按通用后端设计，后续大前端直接复用。

**非目标**：账号体系、OAuth、token 发放、session、管理后台、WebSocket、推荐结果缓存、多实例限流。

## 3. 已核验事实

1. 全仓无 `@RestController`，`pom.xml` 只有 `spring-boot-starter-webflux`，`server.port=18180`。
2. `GET /v2/key/info` 一次调用即可拿到 `info.user.id`、`info.user.factionId`、`info.access.type`、`info.access.faction`，现有 `TornApiKeyDTO` + `TornApiKeyVO` 已封装。
3. 机器人绑定入口 `BindKeyStrategyImpl` 已实现同样的校验，可抽取复用。
4. Key 三类使用边界已分离：`getEnableKey` / `getFactionKey` / `getKeyByUserId`。
5. 帮派白名单为 `torn_setting_faction` 表现存行。
6. 岗位在库内口径为 `Muscle#2`（无空格），页面显示为 `Muscle #2`（有空格），同一 OC 存在重名岗位。
7. `caffeine` 已是项目依赖。
8. 现有异常体系为 `BizException(code, msg)`。

## 4. 总体设计

### 4.1 分层

```
Controller（只做参数接收、凭证注入、返回 ApiResult）
  → ApiCall（统一把阻塞调用下沉 boundedElastic，防止堵 event loop）
    → 领域适配服务（torn.service.faction.oc.web）
      → 既有领域服务（TornOcRecommendService 等）
```

`ApiCall` 是包住"WebFlux 里调用阻塞代码"的唯一入口。禁止在 Controller 里直接写 `subscribeOn`。

### 4.2 鉴权链

```
Authorization: Bearer <16 位 Torn Key>
  → ApiCredentialFilter 取出凭证（缺失即 401）
  → ApiCredentialResolver（接口，策略模式）→ TornKeyCredentialResolver
      → GET /v2/key/info（复用 TornApiKeyDTO/VO）
      → 校验 keyLevel ∈ {LIMIT, FULL}
      → 校验 factionId 在白名单
      → 产出 ApiSubject(userId, factionId, keyId, keyLevel, hasFactionAccess)
  → 写入 exchange attributes，Controller 通过参数解析获取
```

- **凭证只从 Header 读取**，不接受 query 参数（避免进入反代与访问日志）。
- `TornKeyCredentialResolver` 是策略接口的唯一实现；将来接 token 或 session 时新增实现类并调整选择顺序，Controller 与契约不变。
- 缓存：`ApiSubjectCache`（Caffeine）以 `sha256(key)` 为键，10 分钟过期，最大 5000 条。**这是 Torn 配额保护，不是业务结果缓存。**
- 错误信息统一：Key 无效与非本帮派 Key 一律返回同一个 `KEY_INVALID`，不透露 Key 是否存在。
- **`factionId` 一律取自凭证，不接受请求参数**，从根上消除越权查询。
- 校验 Key 级别时不读取 `torn_api_key` 表：插件用户可能没绑过机器人，鉴权完全无状态。

### 4.3 统一响应与错误码

```java
public record ApiResult<T>(int code, String message, T data) {
    public static <T> ApiResult<T> ok(T data);
    public static <T> ApiResult<T> fail(ApiErrorCode code);
}
```

`ApiErrorCode` 为枚举，携带 `code`、`httpStatus`、`message`：

| code | HTTP | 含义 |
| --- | --- | --- |
| 0 | 200 | 成功 |
| 1001 | 401 | 缺少凭证 |
| 1002 | 401 | 凭证无效或不属于已开通帮派 |
| 1003 | 403 | Key 级别不足（非 Limited/Full） |
| 1004 | 403 | 帮派未开通 |
| 1005 | 409 | 已绑定，不覆盖 |
| 2001 | 429 | 触发限流 |
| 2002 | 504 | 上游 Torn 超时 |
| 5000 | 500 | 未预期错误 |

`ApiExceptionAdvice` 统一把 `ApiException`、`BizException`、未捕获异常映射为 `ApiResult`；未预期异常只记录服务端日志，响应体不带堆栈与内部路径。

### 4.4 限流

单实例内存令牌桶（`ConcurrentHashMap` + 时间窗口），不引入新依赖：

- 每 Key：60 次/分钟；
- 每 IP：120 次/分钟（IP 取自 `X-Forwarded-For` 首段，依赖反代正确透传）；
- 超限返回 429 并带 `Retry-After`。

多实例部署时该实现失效，需换 Redis，本期明确不做，并在类注释写明适用前提。

### 4.5 版本与兼容

- 路径版本：`/api/v1`；破坏性变更开 `/api/v2` 并与 v1 并存至少一个版本周期。
- 字段只增不改不删；新增字段必须可缺省。
- `GET /api/v1/system/ping` 返回 `serverTime`、`apiVersion`、`minClientVersion`，插件据此提示升级而不是报错。
- DTO 与契约文档同源维护；后续生成 OpenAPI 时以此为基础，不在本期引入生成器。

### 4.6 部署与反代

- nginx 容器终止 TLS：443 → 应用 18180，应用端口不对外暴露。
- 应用开启 `server.forward-headers-strategy=framework`，保证限流与日志拿到真实客户端 IP。
- CORS 走配置项白名单，至少包含 `https://www.torn.com`（Torn PDA 内嵌 WebView 同为该 origin）；允许 `Authorization` 头，预检缓存 600 秒。
- HSTS 与 http→https 跳转在 nginx 层配置，应用不重复实现。

## 5. 接口契约

### 5.1 GET /api/v1/system/ping

```json
{"code":0,"message":"ok","data":{"serverTime":"2026-10-20T16:00:00","apiVersion":"v1","minClientVersion":"1.0.0"}}
```

无需鉴权。

### 5.2 GET /api/v1/oc/recommend

需鉴权。无请求参数（帮派取自凭证）。

```json
{
  "code": 0, "message": "ok",
  "data": {
    "status": "RECOMMEND_AVAILABLE",
    "generatedAt": "2026-10-20T16:00:00",
    "expiresAt": "2026-10-20T16:10:00",
    "current": {"ocId": 2336580, "ocName": "Ship Happens", "position": "Interrogator", "slotKey": "2336580#interrogator", "hasProgress": true},
    "recommendations": [
      {"ocId": 2336582, "ocName": "Bidding War", "rank": 8, "position": "Muscle#2", "slotKey": "2336582#muscle#2", "score": 812.5, "readyTime": "2026-10-23T02:13:20", "reason": "..."}
    ]
  }
}
```

`status` 枚举（后端唯一判定来源，插件只做渲染分支）：

| 值 | 含义 | 插件表现 |
| --- | --- | --- |
| `RECOMMEND_AVAILABLE` | 存在优于当前岗位的候选 | 推荐项排最前并标记可换 |
| `ALREADY_BEST` | 已加入且无更优 | 保持页面原顺序 |
| `JOINED_WITH_PROGRESS` | 有进度，仅同队可换位 | 只在同队内提示，不引导退队 |
| `DISABLED_OC` | 当前 OC 被禁用 | 提示需更换 |
| `PASS_RATE_INSUFFICIENT` | 当前岗位成功率不足 | 展示实测值与要求值 |
| `NO_VACANT` | 无空缺岗位 | 仅展示状态 |
| `NOT_BOUND` | 未绑定 Key | 引导绑定 |
| `FACTION_NOT_ENABLED` | 帮派未开通 | 提示未开通 |

`slotKey` 生成规则（后端唯一实现，插件按同样规则处理页面文本）：
`ocId + '#' + position.toLowerCase().replaceAll("\\s+", "")`。

`expiresAt` 由后端给出（默认 10 分钟），插件只比较时间，不自行定义 TTL。

### 5.3 POST /api/v1/oc/observations

需鉴权。只接受"空位 + 当前用户自己"的成功率；有人岗位由后端同步，不接受上报。

```json
{"observations":[{"ocId":2336580,"position":"Muscle#2","successRate":71,"observedAt":"2026-10-20T15:58:00"}]}
```

- 单次最多 100 条，超出整批拒绝（不静默截断）。
- `userId`、`factionId` 一律取自凭证，请求体中出现一律忽略。
- 响应：`{"accepted":2,"skipped":1}`，`skipped` 表示值未提高或落在去重窗口内。

### 5.4 POST /api/v1/bind

需鉴权（凭证即待绑定 Key）。请求体 `{"apiKey":"..."}`，与 Header 必须一致，避免"用 A 的 Key 绑 B"。

- 校验通过后写 `torn_api_key`（加密由 TypeHandler 完成）并加入内存池；
- 该 `userId` 已有 Key 时**不覆盖**，返回 `1005` 与掩码后的 Key（`abcd****wxyz`）；
- 异步触发既有用户数据同步（复用 `TornUserService#updateUserData` 与 `TornUserDataService#spiderData`），失败不影响绑定结果。

## 6. 观测数据设计

表 `torn_oc_success_observation`：

| 列 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT | 主键 |
| faction_id | BIGINT | 帮派 |
| oc_id | BIGINT | OC |
| position | VARCHAR(64) | 岗位，库内口径 |
| user_id | BIGINT | 观察者（同时是成功率所属人） |
| success_rate | INTEGER | 成功率百分比 |
| observed_at | TIMESTAMP | 页面读数时间 |
| create_time / update_time | TIMESTAMP | 审计 |

- 唯一键 `(oc_id, position, user_id)`。
- 写入用 `INSERT ... ON CONFLICT DO UPDATE SET success_rate = GREATEST(...)`，一次 SQL 完成"高覆盖低、有值覆盖无值"。
- 同值 30 分钟去重：写入前比对 `observed_at` 与现有值，命中则计入 `skipped`。该判断放在 SQL 的 `WHERE` 里，不做应用层读改写。
- 消费端按 `observed_at` 做 7 天 TTL，过期数据不参与任何计算。

## 7. 包与文件规划

### 7.1 新增：`pn.torn.goldeneye.web`（按职责拆四个子包）

**`web.controller`**

| 文件 | 说明 |
| --- | --- |
| `OcApiController.java` | `/api/v1/oc/*`：recommend、observations、bind |
| `SystemApiController.java` | `/api/v1/system/ping` |

**`web.security`**

| 文件 | 说明 |
| --- | --- |
| `ApiCredentialResolver.java` | 策略接口：`ApiSubject resolve(String rawCredential)` |
| `TornKeyCredentialResolver.java` | Torn Key 实现：调 `/v2/key/info`、校验级别与白名单 |
| `ApiSubject.java` | record：userId、factionId、keyId、keyLevel、hasFactionAccess |
| `ApiSubjectCache.java` | Caffeine 缓存，键为 `sha256(key)` |
| `FactionWhitelist.java` | `torn_setting_faction` 白名单缓存（5 分钟刷新） |
| `ApiCredentialFilter.java` | 解析 Header、调用解析器、塞入 exchange attributes |

**`web.support`**

| 文件 | 说明 |
| --- | --- |
| `ApiResult.java` | 泛型响应信封 |
| `ApiErrorCode.java` | 枚举：code / httpStatus / message |
| `ApiException.java` | 携带 `ApiErrorCode` 的业务异常 |
| `ApiExceptionAdvice.java` | 全局异常映射 |
| `ApiCall.java` | 阻塞调用下沉 `boundedElastic` 的唯一封装 |
| `RateLimitInterceptor.java` | Key/IP 双维度令牌桶 |
| `ApiWebConfiguration.java` | CORS、拦截器、`forward-headers` 相关配置注册 |

**`web.dto.v1`**

| 文件 | 说明 |
| --- | --- |
| `BindReqDTO.java` / `BindRespVO.java` | 绑定 |
| `OcRecommendRespVO.java` / `OcRecommendItemVO.java` / `OcCurrentVO.java` | 推荐 |
| `OcObservationReqDTO.java` / `OcObservationItemDTO.java` / `OcObservationRespVO.java` | 上报 |
| `SystemPingVO.java` | 版本信息 |

### 7.2 新增：`pn.torn.goldeneye.torn.service.faction.oc.web`

| 文件 | 说明 |
| --- | --- |
| `OcWebRecommendService.java` | 调 `TornOcRecommendService`，组装 status、current、expiresAt、slotKey |
| `OcSuccessObservationService.java` | 批次校验、调用 DAO upsert、统计 accepted/skipped |

### 7.3 新增：`pn.torn.goldeneye.torn.service.data`

| 文件 | 说明 |
| --- | --- |
| `TornKeyCredentialService.java` | `validate(String rawKey)` 统一走 `/key/info` 与级别判定；`bindIfAbsent(...)` 实现不覆盖语义 |

### 7.4 新增：repository 三层与资源

| 文件 | 说明 |
| --- | --- |
| `repository/model/faction/oc/TornOcSuccessObservationDO.java` | 观测实体 |
| `repository/mapper/faction/oc/TornOcSuccessObservationMapper.java` | Mapper 接口 |
| `resources/mapper/faction/oc/TornOcSuccessObservationMapper.xml` | upsert SQL |
| `repository/dao/faction/oc/TornOcSuccessObservationDAO.java` | `ServiceImpl` 包装 |

### 7.5 新增：常量与工具

| 文件 | 说明 |
| --- | --- |
| `constants/torn/enums/TornOcRecommendStatusEnum.java` | 状态枚举 |
| `constants/web/ApiConstants.java` | 版本号、批次上限、TTL 默认值 |
| `utils/OcSlotKeyUtils.java` | `of(ocId, position)`，唯一 normalize 实现 |

### 7.6 修改

| 文件 | 改动 |
| --- | --- |
| `napcat/strategy/manage/BindKeyStrategyImpl.java` | 删除内部的 `/key/info` 调用与级别判定，改为委托 `TornKeyCredentialService#validate`；替换语义仍留在本类 |
| `application.yml` | 新增 `api` 段：cors 白名单、限流阈值、推荐 TTL、最低客户端版本 |
| `db/changelog/db.changelog-master.yaml` | 追加 1.9.3 的 include |
| `pom.xml` | 不新增依赖（webflux、caffeine 已存在） |

### 7.7 数据库变更：`db/changelog/1.0.1-2.0.0/1.9.3/oc-observation.yaml`

- `createTable` `torn_oc_success_observation`；
- 唯一约束 `uk_oc_position_user`；
- 索引 `idx_faction_observed`（`faction_id, observed_at`）；
- 不新增其他表、不改既有表。

## 8. 测试方案

只新增 2 个测试类：

**`OcApiControllerTest`**（`WebTestClient`，Mock 解析器与领域服务）

1. 无 Authorization → 401 / `1001`；
2. Key 级别为 Minimal → 403 / `1003`；
3. 帮派不在白名单 → 403 / `1004`；
4. 正常请求 → 200，返回 `status` 与 `slotKey` 正确；
5. CORS 预检 `OPTIONS` 返回允许头。

**`OcSuccessObservationServiceTest`**（真实库回放，不用 Mock 验证 SQL）

1. 同键低值不覆盖高值；
2. 同键高值覆盖低值；
3. 30 分钟内同值重复上报计入 `skipped`；
4. 批次超 100 条整批拒绝。

不新增：`ApiResult`/`ApiErrorCode` 枚举测试、`TornOcRecommendService` 的重复测试、`TornKeyCredentialService` 的 Torn 调用 Mock 测试（该链路已被 `spiderKey` 日任务覆盖，改为真实回放一次）。

## 9. 风险与对策

| 风险 | 对策 |
| --- | --- |
| WebFlux 中误用阻塞调用拖垮 event loop | 所有 Controller 方法返回 `ApiCall.call(...)` 的结果，代码评审以此为门禁 |
| 反代后 IP 取错导致限流失效 | 配置 `forward-headers-strategy`，并在预发用 `X-Forwarded-For` 实测一次 |
| 用户 Key 入池后被业务调用消耗用户配额 | 绑定成功后异步同步用户数据一次；池内使用边界按现有三类 Key 规则，不额外扩大 |
| 错误响应泄露 Key 是否存在 | 统一 `KEY_INVALID`，日志只打 `keyHash` 前 8 位 |
| 观测上报被刷 | 鉴权 + 每 Key 限流 + 唯一键 upsert，重复上报天然幂等 |
| CORS 配置成通配 | 白名单来自配置，默认空；空则拒绝跨域 |

## 10. 明确不做

账号体系、token 发放、JWT、session、管理后台、WebSocket、推荐结果缓存、Redis 限流、OpenAPI 生成器、观测数据的定时清理任务（先人工评估数据量）。

## 11. 完成条件

1. 鉴权矩阵 5 条用例全部通过；
2. 真号上 `/oc/recommend` 返回本人的正确推荐与状态；
3. 观测上报在同一键上满足"高覆盖低、有值覆盖无值"；
4. 反代后限流按真实 IP 生效（预发实测一次）；
5. 日志与响应中无完整 Key；
6. 机器人 `#绑定Key` 回归通过（委托改造未改变行为）。

## 12. 实施顺序

1. `TornKeyCredentialService` 抽取 + `BindKeyStrategyImpl` 委托改造（先保行为不变并回归）；
2. `web.support`（信封、错误码、`ApiCall`、异常、限流、CORS）；
3. `web.security`（解析器、缓存、白名单、过滤器）；
4. `system/ping` 打通鉴权与反代；
5. `oc/recommend`；
6. `oc/observations` 与数据库变更；
7. `bind`；
8. 真机联调（插件侧配合）。
