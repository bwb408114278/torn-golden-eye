# Java 代码规范

## 元信息

- 文档类型：Java代码规范 知识库
- 适用项目：Golden-Eye
- 适用版本：1.2.0及以上
- 最后更新：2026.07.15
- 维护人：Bai
- 状态：有效

---

## 摘要

本文定义本项目 Java 后端代码规范，包括命名、分层、异常处理、日志、DTO、数据库访问、事务、测试和安全要求。

在新增、修改、重构 Java 代码时必须遵守本文规范。

## 适用范围

本文适用于：

- Java 后端代码开发
- Spring Boot 项目
- 新功能开发
- Bug 修复
- 代码重构
- 单元测试
- AI 自动修改代码

## 关键词

Java、Spring Boot、Service、DAO、Mapper、DTO、VO、DO、Exception、Transaction、JUnit、Mockito、日志、代码规范

---

# 1. 基本原则

## 必须遵守

- 代码应清晰、简单、可维护。
- 优先复用项目已有工具类、组件、异常类、常量、枚举。
- 不要引入不必要的新依赖。
- 不要修改与当前任务无关的文件。
- 不要做无关格式化。
- Sonar编码规范
- 遵守设计模式原则，如果需要使用设计模式需要确认后可以使用
- 不要复制粘贴大段重复代码。
- 复杂逻辑必须拆分成清晰的小方法。
- 所有外部输入必须校验。
- 所有关键业务操作必须有测试或明确说明无法测试的原因。
- 相同业务应当放在同一个包下，同个包下的文件或包过多时新建一个包并移入相应的包和文件

## 禁止

- 禁止直接返回数据库 DO 给前端。
- 禁止吞掉异常不处理。
- 禁止在日志中打印密码、Token、身份证号、银行卡号、密钥等敏感信息。
- 禁止硬编码业务魔法值，应使用常量或枚举。
- 禁止在循环中执行大量数据库查询导致 N+1 问题。
- 禁止绕过权限校验和数据权限校验。
- 禁止一个包下堆放大量文件

---

# 2. 命名规范

## 包命名

包名必须全小写，使用公司或项目统一前缀。

正确示例：

```java
com.example.order.service
com.example.order.controller
com.example.order.repository
```

错误示例：

```java
com.example.Order.Service
com.example.orderService
```

## 类命名

类名使用 PascalCase。

常见后缀：

| 类型            | 命名示例                                                     |
|---------------|----------------------------------------------------------|
| Service 类     | `UserService`                                            |
| DAO           | `UserDao`                                                |
| DTO           | `CreateUserDTO` / `UserQueryParam` / `UserQueryReq`      |
| VO / Response | `UserResp` / `UserDetailVO`                              |
| DO            | `UserDO`                                                 |
| Mapper        | `UserMapper`                                             |
| Config        | `SecurityConfig`                                         |
| Properties    | `JwtProperty`                                            |
| Exception     | `UserNotFoundException`                                  |
| Enum          | `OrderStatusEnum`                                        |
| Constant      | `UserConstants`                                          |
| 抽象类           | `BaseUserService` / `AbastractUserService`               |
| 接口实现类         | `UserServiceImpl`                                        |
| 使用了设计模式的类     | `UserDataAdapter` / `UserLoginProxy` / `UserAuthFactory` |

## 方法命名

方法名使用 lowerCamelCase，表达动作和意图。

推荐：

```java
createUser()

getUserById()

updateOrderStatus()

validatePermission()

calculateTotalAmount()
```

不推荐：

```java
doUser()

handle()

process()

test()
```

除非上下文非常明确，否则不要使用过于笼统的方法名。

## 变量命名

变量名使用 lowerCamelCase。

正确：

```java
Long userId;
String orderNo;
BigDecimal totalAmount;
```

错误：

```java
Long user_id;
String OrderNo;
BigDecimal total_amount;
```

## 常量命名

常量使用大写蛇形命名。

```java
private static final int DEFAULT_PAGE_SIZE = 20;
private static final String DEFAULT_TIME_ZONE = "Asia/Shanghai";
```

## 布尔变量命名

布尔变量推荐使用：

```java
isEnabled
        hasPermission
canCancel
        shouldRetry
```

避免：

```java
flag
        status
check
```

---

# 3. 异常处理规范

## 业务异常

业务异常必须使用统一异常类。

推荐：

```java
throw new BizException("用户不存在");
```

不推荐：

```java
throw new RuntimeException("用户不存在");
```

## 禁止

- 禁止吞掉异常。
- 禁止直接把内部异常堆栈返回给前端。
- 禁止用字符串硬编码错误码。
- 禁止用异常控制正常业务流程，除非项目已有约定。

---

# 4. 日志规范

在类的头部使用SLF4J：

```java

@Slf4j
@Service
public class UserServiceImpl {
}
```

## 必须记录日志的场景

- 关键业务状态变化
- 第三方接口调用失败
- 支付、订单、权限等关键操作
- 数据不一致
- 定时任务开始和结束
- 异常被捕获并处理

## 日志级别

| 级别    | 使用场景          |
|-------|---------------|
| debug | 开发调试信息        |
| info  | 关键业务流程        |
| warn  | 可恢复异常、异常输入、重试 |
| error | 系统异常、不可恢复错误   |

## 禁止记录敏感信息

禁止输出：

- 密码
- Token
- Session
- Cookie
- 身份证号
- 银行卡号
- 手机号完整明文
- 邮箱完整明文，视项目要求
- 密钥、AccessKey、SecretKey

错误示例：

```java
log.info("login request: {}",request);
```

如果 request 中包含密码或 token，则禁止直接打印。

推荐：

```java
log.info("user login attempt, username={}",maskUsername(request.getUsername()));
```

---

# 5. 事务规范

事务注释`@Transactional`必须加在方法上而不是注释上。

示例：

```java

@Transactional
public void cancelOrder(Long orderId) {
    // business logic
}
```

要求：

- 涉及多次数据库写操作时必须考虑事务。
- 只读查询可使用 `@Transactional(readOnly = true)`。
- 避免事务方法中执行耗时外部调用。
- 注意 Spring AOP 自调用导致事务不生效的问题。

禁止：

```java

@Transactional
public void process() {
    externalPaymentClient.pay();
    orderRepository.save(order);
}
```

如果外部调用耗时或不可控，应谨慎设计事务边界。

---

# 6. 数据库访问规范

要求：

- 查询必须考虑索引。
- 如果列表查询返回行数大于100，必须分页。
- 批量操作优先，避免 N+1。
- 不要 `SELECT *`，除非项目 ORM 自动处理或确有必要。
- 删除和更新操作必须有明确条件。
- 编写`xml`文件时，编辑主表对应的xml，禁止编辑无关或子表的xml
- 重要数据变更必须记录操作日志或审计日志。

## 分页

列表接口必须分页。

示例：

```java
Page<UserEntity> page = userRepository.findAll(pageable);
```

## 避免 N+1

不推荐：

```java
for(Order order :orders){
User user = userRepository.findById(order.getUserId()).orElse(null);
}
```

推荐：

```java
List<Long> userIds = orders.stream()
        .map(Order::getUserId)
        .distinct()
        .toList();

Map<Long, User> userMap = userRepository.findByIdIn(userIds).stream()
        .collect(Collectors.toMap(User::getId, Function.identity()));
```

---

# 7. 时间和金额规范

## 时间

推荐使用：

```java
LocalDate
        LocalDateTime
```

## 金额

金额必须使用：

```java
BigDecimal
```

禁止使用：

```java
float
double
```

BigDecimal 比较应使用：

```java
amount.compareTo(BigDecimal.ZERO) >0
```

不要使用：

```java
amount.equals(BigDecimal.ZERO)
```

因为 scale 可能不同。

---

# 8. 安全规范

必须遵守：

- 所有需要登录的接口必须校验认证。
- 涉及用户数据时必须校验数据归属。
- 涉及管理操作时必须校验权限。
- 输入必须校验长度、格式、范围。
- 禁止 SQL 拼接。
- 文件上传必须校验类型和大小。
- 禁止信任客户端传入的 userId、role、tenantId 等关键字段。
- 禁止返回不必要的敏感字段。

---

# 9. 测试规范

## 单元测试

推荐使用：

- JUnit 5
- Mockito

测试命名推荐表达业务场景：

```java
createUser_success()
createUser_userExists()
cancelOrder_statusInvalid()
```

## 必须覆盖

- 正常流程
- 参数边界
- 异常流程
- 权限校验
- 状态流转
- 核心计算逻辑

## 禁止

- 禁止只测试 getter/setter。
- 禁止依赖测试执行顺序。
- 禁止测试之间共享可变状态。
- 禁止单元测试真实调用外部支付、短信、邮件等服务。

---

# 10. DTO / VO / DO 规范

## DTO

DTO 只用于数据传输，不应包含复杂业务逻辑。

请求 DTO 应明确校验：

```java
@NotBlank
private String username;

@NotNull
private Long roleId;
```

## VO

VO 只暴露前端需要的字段。

禁止直接复用 DO 作为 VO。

## DO

DO 只映射数据库字段，不承载复杂业务逻辑。

DO 应与数据库字段保持清晰映射。

---

# 11. Service 规范

Service 层负责业务编排和事务边界。

禁止：

- 在 Controller 中编写复杂业务逻辑。
- Service 直接依赖前端对象。
- Service 方法返回数据库 DO 给前端。
- 一个 Service 方法包含过多职责。

复杂 Service 方法应拆分为：

```text
validate
load
calculate
persist
assemble
```

---

# 12. Git 提交规范

提交前必须执行：

```text
编译检查
单元测试
静态检查
敏感信息检查
Git diff 检查
```

提交信息应简洁表达变更目的。

推荐：

```text
feat: add user role management
fix: correct order status transition
refactor: simplify payment service
```

禁止提交：

- 密码
- Token
- 私钥
- `.env`
- 临时日志
- 编译产物
- IDE 临时文件
- 无关格式化修改

---

# 13. 注释与 Javadoc 规范

## 基本要求

- 注释必须解释业务语义、约束或设计原因，禁止仅重复字段名、类名或方法名。
- 新增和修改的公开类型、数据模型和公共 API 必须提供完整、准确的 Javadoc。
- Javadoc 内容必须与当前实现保持一致；修改字段、参数、返回值或异常契约时必须同步更新注释。
- `@Override` 方法可省略方法 Javadoc；如果覆盖实现补充了父契约没有表达的重要限制，则应补充说明。

## POJO、DTO、VO、DO 字段

POJO、DTO、VO、DO 的每个业务字段必须使用 Javadoc 说明业务含义。继承自父类的字段不在子类重复注释。

```java
/**
 * 帮派ID。
 */
private Long factionId;

/**
 * 是否启用自动规划。
 */
private Boolean enabled;
```

禁止仅写无信息量注释：

```java
/**
 * factionId。
 */
private Long factionId;
```

## record 组件

`record` 组件必须通过类型 Javadoc 的 `@param` 逐一说明。`@param` 名称、顺序必须与 record 声明完全一致。

```java
/**
 * OC岗位分配结果。
 *
 * @param userId 用户ID
 * @param slotCode 岗位编码
 * @param joinAt 建议加入时间
 */
public record OcAssignment(long userId, String slotCode, LocalDateTime joinAt) {
}
```

## 接口和抽象类

- 接口和抽象类必须提供类型 Javadoc，说明职责、使用边界和实现约束。
- 接口或抽象类自行声明的字段和方法必须完整注释。
- 方法 Javadoc 必须包含方法用途、全部 `@param`、非 `void` 方法的 `@return`，以及属于调用契约的 `@throws`。
- 仅继承且未重新声明的方法无需重复注释。

## 公共和受保护方法

除 `@Override` 方法外，新增或修改的 `public`、`protected` 方法必须提供方法头 Javadoc。

```java
/**
 * 根据快照生成指定模式的OC新队规划。
 *
 * @param snapshot 同一规划周期内的不可变快照
 * @param mode 规划模式
 * @return 包含推荐分支和备选分支的规划结果
 * @throws IllegalArgumentException 模式或快照参数无效时抛出
 */
public OcNewTeamPlan plan(OcPlanningSnapshot snapshot, OcPlanMode mode) {
    // ...
}
```

私有方法在业务意图不能通过命名和结构直接表达时应补充注释，但不强制机械添加无信息量 Javadoc。

## Liquibase 表和字段注释

- `createTable` 必须通过 `remarks` 提供表注释。
- `createTable.columns` 中每个 `column` 必须通过 `remarks` 提供字段注释，包括主键、逻辑删除和审计时间字段。
- 优先使用 Liquibase 原生 `remarks`，禁止在同一变更中重复使用原生 `COMMENT ON` SQL。
- 注释必须与 Java DO、默认值、约束和初始化数据语义一致。

```yaml
- createTable:
    tableName: torn_setting_example
    remarks: 示例配置表
    columns:
      - column:
          name: id
          type: BIGINT
          remarks: 主键ID
          constraints:
            primaryKey: true
            nullable: false
      - column:
          name: enabled
          type: BOOLEAN
          defaultValueBoolean: true
          remarks: 是否启用
          constraints:
            nullable: false
```

## 提交前注释检查

提交新增数据模型、公共 API 或 Liquibase 建表变更前，必须检查：

- 表和所有字段是否都有准确的 `remarks`；
- POJO、DTO、VO、DO 的业务字段是否都有 Javadoc；
- record 的所有组件是否都有对应 `@param`；
- 接口和抽象类是否完整说明职责与契约；
- 非 `@Override` 的公共、受保护方法是否具备用途、参数、返回值和异常说明；
- 注释是否与实际代码、数据库约束和默认值一致。
