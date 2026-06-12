# Java 代码规范

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
- 不要复制粘贴大段重复代码。
- 复杂逻辑必须拆分成清晰的小方法。
- 所有外部输入必须校验。
- 所有关键业务操作必须有测试或明确说明无法测试的原因。
- Sonar编码规范
- 遵守设计模式原则，如果需要使用设计模式需要确认后可以使用

## 禁止

- 禁止直接返回数据库 DO 给前端。
- 禁止吞掉异常不处理。
- 禁止在日志中打印密码、Token、身份证号、银行卡号、密钥等敏感信息。
- 禁止硬编码业务魔法值，应使用常量或枚举。
- 禁止在循环中执行大量数据库查询导致 N+1 问题。
- 禁止绕过权限校验和数据权限校验。

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

| 类型            | 命名示例                                                |
|---------------|-----------------------------------------------------|
| Service 类     | `UserService`                                       |
| DAO           | `UserDao`                                           |
| DTO           | `CreateUserDTO` / `UserQueryParam` / `UserQueryReq` |
| VO / Response | `UserResp` / `UserDetailVO`                         |
| DO            | `UserDO`                                            |
| Mapper        | `UserMapper`                                        |
| Config        | `SecurityConfig`                                    |
| Properties    | `JwtProperty`                                       |
| Exception     | `UserNotFoundException`                             |
| Enum          | `OrderStatusEnum`                                   |
| Constant      | `UserConstants`                                     |

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

# 6. 异常处理规范

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

# 7. 日志规范

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

# 9. 事务规范

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

# 10. 数据库访问规范

要求：

- 查询必须考虑索引。
- 如果列表查询返回行数大于100，必须分页。
- 批量操作优先，避免 N+1。
- 不要 `SELECT *`，除非项目 ORM 自动处理或确有必要。
- 删除和更新操作必须有明确条件。
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

# 13. 时间和金额规范

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
amount.compareTo(BigDecimal.ZERO) > 0
```

不要使用：

```java
amount.equals(BigDecimal.ZERO)
```

因为 scale 可能不同。

---

# 14. 安全规范

必须遵守：

- 所有需要登录的接口必须校验认证。
- 涉及用户数据时必须校验数据归属。
- 涉及管理操作时必须校验权限。
- 不要信任前端传入的角色、权限、金额、用户 ID。
- SQL 查询必须使用参数绑定，禁止字符串拼接。
- 文件上传必须校验类型、大小和路径。
- 返回值必须过滤敏感字段。

SQL 错误示例：

```java
String sql = "SELECT * FROM users WHERE name = '" + name + "'";
```

正确示例：

```java
query("SELECT * FROM users WHERE name = ?",name);
```

---

# 15. 测试规范

## 测试框架

优先使用项目已有测试框架，例如：

- JUnit 5
- Mockito
- Spring Boot Test
- Testcontainers
- AssertJ

## 必须覆盖

- 正常路径
- 参数非法
- 数据不存在
- 权限不足
- 状态不允许
- 外部依赖失败
- 边界条件
- Bug 修复对应回归测试

## 单元测试规范

- 必须使用`Test`作为方法名后缀，写清楚测试目的
- 类的头部和测试方法必须添加`@DisplayName`，用中文名称写清

## 禁止

- 禁止为了通过测试修改业务逻辑。
- 禁止删除失败测试而不说明原因。
- 禁止依赖测试执行顺序。
- 禁止测试访问生产环境资源。

---

# 16. POJO类规范

POJO类必须使用Lombok的注释，每个POJO类在不适用`@Data`时必须添加`@ToString`

如果类用作业务判断/接口返回，防止代码篡改数据，必须添加函数构造函数，并使用：

```java
@Getter
@ToString
```

如果类用作接口参数/数据库映射需要写入值，使用

```java
@Data
```

如果类是数据库映射类，必须有一个无参构造函数

如果类是一个子类，必须添加

```java
@EqualsAndHashCode
```

---

# 17. 依赖注入规范

普通类使用Lombok构造器注入，抽象类、接口类优先使用`@Resouce`注入

---


# 19. 注释规范

要求：

- 代码应优先通过命名和结构表达意图。
- 复杂业务规则必须写注释。
- 临时方案必须标记 TODO，并说明原因。
- 不要写无意义注释。
- 每个类的头部必须添加注释，写明类的用处、作者、版本、创建时间(@since)
- POJO的每个变量要添加JavaDoc注释
- 接口类、抽象类的方法除了方法的作用，要写清楚每个变量和返回值的意义

---

# 20. AI Agent 修改代码时的额外要求

AI Agent 修改 Java 代码时必须遵守：

1. 修改前先读取本规范。
2. 修改前先搜索类似实现。
3. 修改前先说明计划。
4. 不要引入新依赖。
5. 不要修改无关文件。
6. 不要做无关格式化。
7. 不要直接返回 Entity。
8. 不要绕过权限校验。
9. 不要吞掉异常。
10. 修改后查看 git diff。
11. 修改后尽量运行测试。
12. 如果测试失败，只修复与本任务相关的问题。
13. 如果不确定项目约定，先提问。