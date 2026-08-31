# 1.6.0 富表格渲染平台与 OC 图片迁移技术方案

## 1. 文档定位

本文是 `feat/table-rendering-1.6.0` 分支上“富表格渲染平台第一轮建设 + **全部 OC 图片表格迁移**”的开发、Review 与验收契约。

- **目标版本：** `1.6.0`
- **当前代码基线：** `4a94534`（当前 `pom.xml` 与 `build/docker-compose.yml` 均为 `1.5.3`）
- **版本标识约定：** 用户将在实施前直接把 `pom.xml` 与 `build/docker-compose.yml` 改为 `1.6.0`，以标记当前开发版本。该变更可能单独提交，也可能与后续提交记录相邻；Review 按文件最终值与本方案判定，不以提交作者/归属判定责任。开发人员不得覆盖、回退或再次修改这两个版本标识，除非用户另行授权。
- **风险等级：** L3
- **原因：** 本轮新增浏览器运行时、Docker 镜像依赖、公共图片渲染能力，并重新引入 OC 只读展示所需的道具快照字段和 Liquibase 迁移；虽不改变 OC 分配/收益业务规则，但涉及生产镜像和持久化兼容。
- **适用对象：** 开发人员、代码 Review 人员、发布验收人员。

本文中的“必须”“禁止”“验收”均为实现与 Review 标准。开发人员只实现 Java、资源、Docker、Schema 和测试；本技术方案由架构侧维护，开发人员不得自行改变其中的业务契约、范围或验收结论。

---

## 2. 决策、背景与当前事实

### 2.1 已确认决策

本轮采用下列路线：

```text
OC / 推荐业务数据
        ↓
OC 图片领域组装器（只产生语义化文档）
        ↓
通用 TableDocument
        ↓
TableImageRenderer 接口
        ↓
HTML/CSS + Playwright Java + 固定 Chromium
        ↓
PNG Base64
        ↓
既有 ImageQqMsg / NapCat 发送链路
```

不采用 Java 调用 Python，不创建 Python 微服务，也不继续扩展 Java2D 的 Emoji 字体补丁。

### 2.2 当前代码事实

1. `src/main/java/pn/torn/goldeneye/utils/image/TableImageUtils.java` 是 Java2D 手工绘制表格实现；存量调用方直接构造二维字符串、行列坐标、合并信息和 `CellStyle`。
2. `src/main/java/pn/torn/goldeneye/base/model/TableDataBO.java` 直接依赖 `TableImageUtils.TableConfig`，不能作为新平台的通用文档模型复用。
3. 当前 OC 图片入口为：
   - `TornFactionOcMsgManager.buildOcTable(...)`；
   - `TornFactionOcMsgManager.buildRecommendTable(...)`；
   - 上层分别由 `OcQueryStrategyImpl`、`OcRecommendStrategyImpl` 和 `TornOcCompleteNoticeService` 使用。
4. 当前 `TornFactionOcMsgManager` / `TornFactionOcMsgTableManager` 中仍存在以二维数组、坐标与 `java.awt.Color` 处理推荐高亮和空位灰化的实现。
5. 当前 `TornFactionOcSlotDO` 没有 `item_requirement` 的本地快照字段；OC 查询图片若要展示缺道具状态，必须由既有 OC 同步链写入快照，**禁止**在图片请求时请求 Torn API。
6. 当前 Dockerfile 仅复制微软雅黑字体；这正是 Java2D 不能稳定画出彩色 Emoji 的技术边界。
7. 当前 `main` 已合并 1.5.3 的股票修复；`pom.xml:14` 当前值为 `1.5.3`。本架构分支虽然从该基线创建，但首个 1.6.0 架构提交必须把项目版本统一升为 `1.6.0`。

### 2.3 本次需要恢复的 OC 展示业务契约

原 OC 图片美化代码已从 1.5.x 发布线完整 revert，原因见第 3 章；本轮并非恢复旧 Java2D 实现，而是在新 HTML 渲染平台上重新实现以下已确认展示语义：

#### 标题时间文案

令：

```text
delta = readyTime - now
```

一张图片只允许出现一种时间文案：

| 条件 | 标题文案 |
|---|---|
| `readyTime == null` | 不追加 |
| `status == Recruiting && now > readyTime` | `已停转` |
| `status in {Recruiting, Planning} && delta > 24h` | `还需空转xx小时xx分钟` |
| `status == Recruiting && now <= readyTime && delta <= 24h` | `xx小时xx分后停转` |
| `status == Planning && now <= readyTime && delta <= 24h` | `预计HH:mm开始执行` |
| `status == Planning && now > readyTime` | 保持当前已确认的预计执行语义，不能静默为空 |

- 24 小时分类使用完整时间精度；仅最终展示时向分钟稳定化。
- 执行时间统一为 `readyTime.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)`。
- 同一图片的标题与 OC 团队状态颜色必须使用入口一次取得的相同 `now`，不得分别调用 `LocalDateTime.now()`。

#### 成员 Emoji 状态

只有 `userId != null` 的槽位显示一个 Emoji：

| 优先级 | 判定 | Emoji |
|---:|---|---|
| 1 | `progress == 0` | `💤` |
| 2 | `requiredItemId != null && requiredItemAvailable == false` | `⚠️` |
| 3 | `progress == 100` | `✅` |
| 4 | `0 < progress < 100` | `⏳` |

约束：

- 空槽没有 Emoji；即使进度为 0 也不能显示 `💤`。
- `progress == null`、小于 0 或大于 100，返回未知状态且不显示 Emoji；未知快照不能伪造成缺道具。
- `💤` 优先于缺道具；同一成员绝不显示两个 Emoji。
- 每次 OC 查询图片仅读取本地快照；不新增 Torn API、DAO N+1 或道具实时查询。

---

## 3. 关于“未携带旧 OC 图片美化代码”的结论

这不是遗漏，而是上轮分支隔离策略的预期结果。

### 3.1 Git 历史事实

旧功能由四个提交引入：`597e2ba`、`52bf833`、`cf2f4a7`、`5ad7369`；随后在 `feat-oc` 上以四个追加 revert 提交 `1af5d9f`、`596fb64`、`78b4dcb`、`a595796` 完整撤回，并被合并进入当前 `main`。

因此，当前分支虽然能在历史图中看到旧提交祖先，但工作树的净效果等价于“旧功能从未加入”：

- `OcImageStatusResolver`、`OcImageTitleFormatter` 和旧图片状态枚举不存在；
- `required_item_id` / `required_item_available` 的 Liquibase 文件不在 master include 中；
- `TornFactionOcMsgManager` 恢复为原 Java2D 二维表格路径；
- 旧功能的测试也随 revert 撤回。

### 3.2 为什么不能简单 cherry-pick

旧实现的核心是“在 Java2D 字符串单元格尾部追加 Emoji”。它恰恰依赖 Java2D 的字体渲染路径，而该路径是本次方块问题的根因。若将旧提交直接 cherry-pick 到 1.6.0：

1. 会先把失效的 Java2D Emoji 重新引入；
2. 会把二维数组/行列坐标/颜色设置与 OC 领域规则再次耦合；
3. 后续迁移到 HTML 时会变成“旧实现接入 + 再拆掉”的重复代码；
4. 还会混淆“旧 1.5.x 修复”与“1.6.0 架构实现”的 Review 基线。

**结论：** 本轮必须重新引入的是“OC 状态领域规则与快照数据契约”，不是旧 Java2D 绘制代码。旧提交和已暂存的两份历史设计文档仅可作为业务语义追溯材料，不得机械搬运源码。

---

## 4. 范围与非目标

### 4.1 本轮范围

1. 将项目版本从 `1.5.3` 升为 `1.6.0`。
2. 新建独立、渲染技术无关的声明式表格文档模型。
3. 新建 `TableImageRenderer` 抽象与 HTML/Chromium 实现。
4. 在 Docker 镜像中固定 Playwright、Chromium 依赖、中文字体、Emoji 字体及许可证文件。
5. 新建受控的单 Browser 生命周期管理：单实例、单并发、超时、资源关闭和一次重建。
6. 恢复 OC 标题时间和成员 Emoji 状态解析，以及 item requirement 本地快照同步。
7. 迁移当前分支全部由 OC 业务产生、最终以图片表格发送的入口；首次实现共覆盖五类：
   - 当前执行中 OC 查询和 OC 即将结束通知共用的 `buildOcTable`；
   - OC 推荐图片；
   - OC 分配建议图片；
   - 可加入 OC 成员图片；
   - 用户 OC 成功率图片。
8. 上述入口中，当前 OC 查询、OC 推荐、OC 分配和完成通知复用同一 OC 状态/槽位展示能力；可加入成员、成功率表属于独立的历史/候选数据表，只迁移渲染模型与视觉能力，不伪造当前 OC 状态 Emoji 或 item snapshot。
9. 保持既有图片消息 Base64 协议、策略命令、数据库查询入口和发送入口不变。

### 4.2 明确不做

1. 不迁移 RW、股票、用户非 OC 业务、拍卖、OC 收益榜/历史收益榜、OC 空转/成功率排行榜、文本图片等其他存量图片。
2. 不修改或删除 `TableImageUtils`、`TextImageUtils`、`TableDataBO`；它们继续服务未迁移调用方。
3. 不把 Java2D 渲染器包一层强行适配到 `TableImageRenderer`，避免为了形式制造兼容层。
4. 不创建 Python 服务、HTTP RPC、消息队列、数据库表、缓存或分布式锁。
5. 不增加当前 OC 图片的 Torn API 调用，不改变 OC 推荐、分配、收益、完成通知或状态同步的业务语义。
6. 不迁移用户自定义主题、动态图表、远程头像、二维码、富文本 HTML、用户传入 CSS/HTML。
7. 不暴露 Chromium DevTools 端口，不允许页面加载网络、文件系统或第三方 CDN 资源。
8. 不新建 `1.6.0-rc*`、`latest` 或其他发行版本语义；本轮唯一镜像标签为 `golden-eye:1.6.0`。

---

## 5. 包规划与职责边界

### 5.1 新建通用图片文档包

```text
pn.torn.goldeneye.utils.image.document
  TableDocument.java
  TableRow.java
  TableCell.java
  TableCellStyleEnum.java
  TableTextOverflowEnum.java

pn.torn.goldeneye.utils.image.render
  TableImageRenderer.java

pn.torn.goldeneye.utils.image.render.html
  HtmlTableImageRenderer.java
  HtmlTableMarkupRenderer.java
  PlaywrightBrowserManager.java
```

#### `document` 包职责

- 只描述图片表格结构与语义：标题、行、单元格、跨行/列、文字内容、样式枚举、溢出策略和输出宽度。
- `TableDocument`、`TableRow`、`TableCell` 采用不可变对象；优先 Java 21 `record`，但只有其字段完全属于稳定值对象时才使用 record。
- 对集合执行防御性复制；不向外暴露可修改列表。
- `TableCellStyleEnum` 仅提供有限的语义样式：`TITLE`、`SECTION`、`TEAM_READY`、`TEAM_WARNING`、`SLOT_FILLED`、`SLOT_EMPTY`、`SLOT_RECOMMENDED`、`SLOT_IDLE`、`MEMBER_FILLED`、`MEMBER_EMPTY`、`FOOTER`。
- `TableTextOverflowEnum` 仅提供当前所需的 `WRAP`、`ELLIPSIS`、`CLIP`，不接受调用方直接给 CSS。
- 单元格 `rowSpan` / `colSpan` 必须大于 0；构造时 fail-fast。第一阶段不实现复杂自动表格布局算法。
- 不依赖 Spring、Playwright、`java.awt`、`TableImageUtils`、OC 类或 HTML 字符串。

#### `render` 包职责

- `TableImageRenderer` 为唯一公共渲染抽象：

```java
String render(TableDocument document);
```

- 返回值为不带 `base64://` 前缀的 PNG Base64，保持与 `TableImageUtils.renderTableToBase64(...)` 的现有语义一致。
- 渲染器异常统一转换为 `BizException`，包含固定、可检索的文档类型/模板信息；不得把业务文本、完整 HTML、Cookie、环境变量或浏览器诊断数据写入日志。
- 不创建“Java2D 实现类”；存量 Java2D 调用保持原样，避免无收益重构。

#### `render.html` 包职责

- `HtmlTableMarkupRenderer`：唯一负责把 `TableDocument` 映射为固定 HTML 片段；所有动态文本必须 HTML escape；所有 class 名由 `TableCellStyleEnum` 映射，不接受外部 class/CSS/URL。
- `HtmlTableImageRenderer`：将已 escape 的 HTML 交给浏览器、设置固定 viewport/DPR、等待布局稳定后截图、编码 PNG Base64；不得解析 OC 数据或做 DAO 查询。
- `PlaywrightBrowserManager`：只管理 Playwright、Browser、Context 和 Page 的生命周期、信号量、超时、一次重建与关闭；不得承载模板、CSS 或业务规则。

> 当前 `utils.image` 包已有 Java2D 工具。新代码按 `document`、`render`、`render.html` 分包，防止该包继续堆积所有模型、HTML 和浏览器生命周期类。

### 5.2 新建 OC 图片领域包

```text
pn.torn.goldeneye.torn.model.faction.oc.image
  OcImageSlotStatusEnum.java

pn.torn.goldeneye.torn.service.faction.oc.image
  OcImageStatusResolver.java
  OcImageTitleFormatter.java
  OcTableDocumentAssembler.java

pn.torn.goldeneye.torn.service.faction.oc
  OcPreparationTimeCalculator.java
```

- `OcImageSlotStatusEnum`：定义唯一 Emoji 和状态含义，空槽/未知不输出 Emoji。
- `OcImageStatusResolver`：只解析 `userId`、`progress`、`requiredItemId`、`requiredItemAvailable` 为唯一状态；无 DAO、API、HTML 或颜色依赖。
- `OcImageTitleFormatter`：只按第 2.3 节输出唯一标题时间文本；无 DAO、API 或浏览器依赖。
- `OcPreparationTimeCalculator`：唯一持有“readyTime 截断到分钟后加 1 分钟”的执行时间公式；`TornOcCompleteNoticeService` 和 Formatter 均复用它，禁止复制 `plusMinutes(1)`。
- `OcTableDocumentAssembler`：将已查好的 OC、槽位、用户、推荐结果和 `now` 组装为 `TableDocument`。它不查询 DAO、不排序以外部未定义业务规则、不调用渲染器。

### 5.3 现有 OC Manager 的职责收敛

`pn.torn.goldeneye.torn.manager.faction.crime.msg` 目前已有两个类，禁止继续增加第三个渲染相关 Manager：

- `TornFactionOcMsgManager`：保留为公共入口、DAO 批量读取、索引构造、一次 `now` 边界获取以及调用 `OcTableDocumentAssembler + TableImageRenderer` 的编排层。
- `TornFactionOcMsgTableManager`：不再供迁移后的两个入口使用；本轮不删除，供其余可能依赖旧表格行生成的存量路径继续使用。
- 迁移后的 OC 路径不得继续生成 `TableDataBO` 后再转 HTML，也不得把 HTML renderer 反向塞入 `TornFactionOcMsgTableManager`。

---

## 6. 详细文件修改清单

### 6.1 构建、版本和配置

#### 修改 `pom.xml`

1. 项目版本由 `1.5.3` 修改为 `1.6.0`。
2. 新增属性：

```text
playwright.version = <Spike 通过后冻结的版本>
```

3. 新增唯一依赖：

```text
com.microsoft.playwright:playwright:${playwright.version}
```

4. 不引入 Thymeleaf、FreeMarker、Selenium、JavaFX、JasperReports、Python bridge 或额外 Web 框架。HTML 骨架由资源文件 + `HtmlTableMarkupRenderer` 完成，避免多套模板引擎。
5. 依赖版本必须为明确固定值，禁止 `LATEST`、版本区间或跟随 Spring Boot 未验证升级。

#### 新增 `src/main/java/pn/torn/goldeneye/configuration/property/TableImageRenderProperty.java`

按当前 `configuration.property` 的 `@Component + @ConfigurationProperties` 方式实现，前缀固定为：

```yaml
table-image-render:
  render-timeout-seconds: 10
  acquire-timeout-seconds: 3
  viewport-width: 1600
  device-scale-factor: 1
```

约束：

- 只配置当前必需的超时、输出宽度和 DPR；不增加开关、动态浏览器路径、任意 CSS 路径或并发配置。
- 并发固定为 1，不暴露为运营参数。
- 值不合法时应用启动 fail-fast，不能在实际 Bot 请求中才抛错。
- 属性类字段需中文 Javadoc；版本标记为 `1.6.0`。

#### 修改 `src/main/resources/application.yml`

新增上述 `table-image-render` 默认块。不得修改现有 datasource、Redis、Bot 或项目环境配置。

#### 修改 `build/Dockerfile`

1. 保持两阶段构建和 `openjdk:21-jdk` 运行阶段，不新建额外 Python/Node 服务阶段。
2. 在构建阶段使用 Maven dependency/plugin 的确定性方式下载 Playwright 所需 Chromium 到固定目录；运行阶段复制已下载的浏览器目录与其依赖。
3. Dockerfile 必须显式安装 Chromium 运行所需 Linux 库；具体包清单以选定 Playwright 版本官方 `install --with-deps chromium` 输出和目标容器实际验证为准，必须锁定在 Dockerfile，不能依赖 NAS 宿主机。
4. 设置固定 `PLAYWRIGHT_BROWSERS_PATH`，构建期与运行期使用同一绝对路径；应用启动不得下载浏览器。
5. 继续复制 `build/fonts/msyh.ttc`、`msyhl.ttc`、`msyhbd.ttc`；新增经容器 Spike 验证的 Emoji 字体至 `/usr/share/fonts/custom/`。
6. 增加字体许可证文件到镜像中可审计路径，例如 `/usr/share/licenses/golden-eye/`；Dockerfile 注释说明来源、版本和许可证。
7. 执行 `fc-cache -f`；镜像验证要检查目标字体可被 Chromium 发现。
8. 不增加 `EXPOSE`、DevTools、`--remote-debugging-port`、宿主机 bind mount 或启动 shell。

> 字体选择门槛：必须在最终 Linux Docker + Chromium 中实际显示 `💤 / ⏳ / ✅ / ⚠️`。不能以 Java `Font.canDisplay`、Windows 截图或单纯字体文件存在替代验证。字体文件和许可证版本由 Spike 结果写入实施提交说明及本方案的验收附录。

#### `build/docker-compose.yml` 版本标识

用户将在开发前将 `golden-eye:1.5.3` 改为 `golden-eye:1.6.0`，作为当前开发版本标识。该文件不属于开发人员功能修改范围；开发人员不得回退、覆盖或把它改为 `rc`、`latest`、digest 等其他标签语义。

### 6.2 通用文档模型与 HTML 渲染器

#### 新增 `utils/image/document/TableDocument.java`

- 不可变根对象，包含 `title`、`List<TableRow>`、固定 `width` 和文档类型字符串。
- `title` 是业务文本，不是 HTML；构造器验证非空、宽度合理、行集合非空。
- 第一阶段不引入 `TableSection`：当前 OC 分隔行可用 `SECTION` 样式跨列单元格表达，避免无调用场景的模型膨胀。

#### 新增 `utils/image/document/TableRow.java`

- 不可变行对象，包含单元格集合；禁止空行。
- 行不保存像素坐标、字体、颜色或 HTML。

#### 新增 `utils/image/document/TableCell.java`

- 不可变单元格对象：文本、`TableCellStyleEnum`、`rowSpan`、`colSpan`、`TableTextOverflowEnum`。
- 文本允许空字符串，但不允许 null；样式与 span 不允许 null/非法。
- 不引入单元格任意属性 Map。

#### 新增 `utils/image/document/TableCellStyleEnum.java` 与 `TableTextOverflowEnum.java`

- 枚举只表达第 5.1 节有限语义，不保存 `Color`/字体对象。
- HTML CSS class 映射集中在 `HtmlTableMarkupRenderer`，不把 CSS class 暴露给业务层。

#### 新增 `utils/image/render/TableImageRenderer.java`

- 定义单一同步 `render(TableDocument)` 方法及完整 Javadoc。
- 使用接口作为依赖倒置边界；不定义 `renderHtml`、`renderUrl`、`renderFile` 等危险扩展 API。

#### 新增 `utils/image/render/html/HtmlTableMarkupRenderer.java`

- 使用 `StringBuilder` 只生成固定结构的 `<article><table><tbody><tr><td>`；这里不是业务层拼 HTML。
- 使用 `HtmlUtils.htmlEscape(...)` 或项目现有等价可信 HTML escape 工具统一 escape 所有动态文本。
- 根据 span 输出受控的 `rowspan` / `colspan`；根据样式枚举输出固定 CSS class；禁止输出动态 attribute、URL、style 属性或 script。
- 生成完整 HTML 时加载 classpath 内的固定 CSS 内容，不能引用外链、`file:`、`data:` 以外资源。

#### 新增 `utils/image/render/html/PlaywrightBrowserManager.java`

- Spring `@Component`，实现 `AutoCloseable` 或使用 `@PreDestroy` 显式释放 `Browser` 和 `Playwright`。
- 应用启动创建一个 Browser；若启动创建失败，应用启动失败，避免系统在收消息后才发现没有浏览器。
- 使用单个 `Semaphore(1, true)` 限制同时渲染数为 1；在 `acquire-timeout-seconds` 内取不到许可则抛 `BizException`。
- 一次 render 创建一个 `BrowserContext` 与一个 `Page`；`finally` 必须关闭 Context 并释放许可。禁止复用 Page，禁止为每张图启动 Browser。
- Page 创建后注册路由：默认 abort 一切非当前内存页面请求；不因本轮模板没有网络资源而放开网络。
- Browser 已断开或渲染过程中确定崩溃时，只允许在同一个请求中关闭旧实例并重建一次；第二次失败直接抛错。重建过程必须由同一渲染许可串行化。
- 日志只记录文档类型、耗时、异常类型；不可打印完整 HTML、用户名称、图片 Base64 或外部配置。

#### 新增 `utils/image/render/html/HtmlTableImageRenderer.java`

- `@Component` 实现 `TableImageRenderer`。
- 将文档交给 `HtmlTableMarkupRenderer`，再通过 `Page.setContent()` 写入，不调用 `navigate(url)`。
- 固定 viewport 和 device scale factor；截图只截取表格根元素，避免浏览器默认页面空白边距与不可控全页高度。
- CSS 必须设置白底、中文/Emoji fallback 字体链、`border-collapse`、语义样式颜色、长文本 overflow 行为和最大宽度。
- 以 PNG bytes 进行 Base64 编码；截图或浏览器异常转换为统一 `BizException("表格图片渲染失败", cause)`。
- 不持有业务 Mapper、DAO、OC 类或策略类引用。

#### 新增资源

```text
src/main/resources/table-image/oc-table.css
src/main/resources/fonts/<emoji-font>.ttf
src/main/resources/fonts/LICENSE-<emoji-font>.txt
```

- CSS 是当前 OC 主题的唯一权威样式文件：标题、分隔行、成员、团队状态、推荐、灰化、页脚。
- 第一阶段只提供一个静态亮色主题；不要建立 ThemeFactory、主题注册表或多套 CSS。
- Emoji 字体和许可证使用最终 Spike 验证版本。若字体许可证要求 attribution/ShareAlike，必须按许可在仓库与镜像中归档；“非盈利”不是跳过许可证义务的理由。

### 6.3 全部 OC 表格的文档组装与入口迁移

#### 修改 `repository/model/faction/oc/TornFactionOcSlotDO.java`

新增字段并提供中文 Javadoc：

```java
private Integer requiredItemId;
private Boolean requiredItemAvailable;
```

- `null` 表示当前同步不能确认或无需求，绝不等价于 false。
- 不存道具名称，显示名称继续从已有物品字典读取；本轮 HTML 成员格只显示警告 Emoji，不增加名称列。
- 更新本轮类 Javadoc `@version` 为 `1.6.0`。

#### 修改 `torn/model/faction/crime/TornFactionCrimeSlotVO.java`

- 只在该 Torn API 槽位模型尚未暴露 `item_requirement` 或可用性时补齐必要字段映射；如已有 `TornFactionCrimeRequireItemVO` 可直接复用，则不重复定义 DTO。
- 不改变 API 请求参数或新增 API 请求。

#### 修改 `torn/manager/faction/crime/TornFactionOcSlotManager.java`

- 在既有 OC 刷新同步中，将 API 一次返回的 slot requirement 写入两个快照字段。
- 第一次槽位创建、已有槽位更新、无人、无 requirement、道具恢复可用必须正确写入/清空快照。
- API 没有 requirement 或本次数据未知时，必须清除/保持为 null，不能写 `false`。
- 将目前循环内对旧槽位 `stream().filter()` 的重复扫描收敛为按 `(ocId, position)` 的本地 Map 索引；仅限本方法，避免 N×M 扫描。索引 key 必须使用明确 record 或静态值对象，不能拼接有歧义的字符串。
- 不新增 DAO 查询次数，不增加逐槽位 update；沿用或改为已有批量/条件更新边界。

#### 新增 Liquibase

```text
src/main/resources/db/changelog/1.0.1-2.0.0/1.6.0/oc-table-image-status.yaml
```

并修改：

```text
src/main/resources/db/changelog/db.changelog-master.yaml
```

要求：

- 为 `torn_faction_oc_slot` 追加 `required_item_id`（兼容 item ID 类型、允许 NULL）和 `required_item_available`（BOOLEAN、允许 NULL）；每列具备中文 `remarks`。
- 新 changeSet 使用稳定唯一 ID、作者 `Bai`；历史 changeSet 一律不改写。
- 不回填、不伪造历史状态、不新增索引。
- master 末尾追加 include，不重排既有 include。

#### 新增 `torn/model/faction/oc/image/OcImageSlotStatusEnum.java`

- 枚举固定 `EMPTY`、`IDLE`、`MISSING_ITEM`、`READY`、`PREPARING`、`UNKNOWN`。
- 只有 `IDLE`、`MISSING_ITEM`、`READY`、`PREPARING` 映射到原始 Unicode Emoji。
- 不放颜色、HTML class、DAO 或格式化逻辑。

#### 新增 `torn/service/faction/oc/image/OcImageStatusResolver.java`

- 纯领域组件；方法参数不超过 4 个，推荐建立 `OcImageSlotState` record 输入，字段为 userId/progress/itemId/itemAvailable，避免四个松散参数扩散。
- 解析顺序严格为：空槽 → 进度合法性 → 空转 → 缺道具 → 完成 → 准备中。
- 不查询数据库、不记录逐槽位日志、不引用浏览器或 `TableDocument`。

#### 新增 `torn/service/faction/oc/OcPreparationTimeCalculator.java`

- 公开单一方法计算计划开始时间；用于图片标题与完成通知。
- 不与现有 `planning.matching.OcPreparationTimeCalculator` 合并或改名：两者职责不同，后者仍是规划匹配时间计算。

#### 修改 `torn/service/faction/oc/TornOcCompleteNoticeService.java`

- 将本类中 `readyTime.truncatedTo(...).plusMinutes(1)` 的计划执行公式委托给新计算器。
- 不修改通知调度、道具提醒、人员状态查询或发送行为。

#### 新增 `torn/service/faction/oc/image/OcImageTitleFormatter.java`

- 纯领域组件，输入 `status`、`readyTime`、`now`，输出标题追加文字。
- 24 小时阈值、Planning 过期语义与计划执行时间必须满足第 2.3 节。
- 不自行调用时钟；`now` 由图片入口传入。

#### 新增 `torn/service/faction/oc/image/OcTableDocumentAssembler.java`

- 只接收已批量查询并已完成排序的 OC/槽位/用户/推荐信息和固定 `now`。
- 负责生成：标题行、OC 分隔行、团队状态单元格、岗位行、成员行、页脚行。
- 将推荐高亮、非推荐空位灰化转换为 `TableCellStyleEnum`，不携带 `Color`、行列下标或 HTML。
- 组装时成员文本为 `昵称[id] + 空格 + Emoji`，Emoji 原字符串保持不变，由 Chromium/字体渲染。
- 当前 OC、推荐、分配三种表格共用“OC 块生成”私有方法，通过参数对象/策略函数表达该块的推荐岗位，不复制三行表格拼装代码。
- 不调用 DAO、Torn API、浏览器或 `TableImageUtils`。

#### 新增 `torn/service/faction/oc/image/OcHistoryTableDocumentAssembler.java`

- 负责组装不含“当前执行 OC”语义的两类 OC 图片：可加入成员表、用户 OC 成功率表。
- 复用 `TableDocument`、`TableRow`、`TableCell` 和有限 `TableCellStyleEnum`；不得复制 HTML、浏览器、字体或 CSS 逻辑。
- 可加入成员表沿用现有六列：Rank、ID、Name、OC名称、岗位、成功率。
- 成功率表沿用现有标题、按 OC 分组的跨列行、等级/岗位/成功率内容与既有筛选结果；不得因为迁移渲染器改变用户的等级范围、帮派岗位门槛或成功率计算。
- 这两类表不显示当前 OC 状态 Emoji、`readyTime`、道具警告或 item snapshot；它们没有对应的当前槽位事实。
- 对当前 `OcRateQueryStrategyImpl` 中反复按 OC 名过滤用户/配置列表的行为，可在组装前以 `ocName -> List` 的本地 Map 一次性索引消除重复扫描；不改变结果排序或 DAO 查询。

#### 修改 `napcat/strategy/faction/crime/OcMemberStrategyImpl.java`

- 删除本类对 `TableImageUtils`、`java.awt`、二维字符串表的直接依赖。
- 注入 `OcHistoryTableDocumentAssembler` 与 `TableImageRenderer`，以既有已查询、已排序的空闲成员和用户 Map 生成文档并渲染。
- 保持命令解析、空结果文案、DAO 查询、排序和 `buildImageMsg(...)` 调用不变。

#### 修改 `napcat/strategy/faction/crime/OcRateQueryStrategyImpl.java`

- 删除对 `TornFactionOcMsgTableManager`、`TableImageUtils`、`java.awt.Color` 和旧行列拼装方法的依赖。
- 保留当前查询、按 7 级资格筛选、帮派覆盖成功率、排序和空结果文案；将最终已准备数据交给 `OcHistoryTableDocumentAssembler + TableImageRenderer`。
- 将仅服务 Java2D 坐标/样式的 `TITLE_STYLE`、`CONTENT_STYLE`、`buildPositionRow`、`buildPassRateRow`、`calcMaxColumnSize` 删除；不得保留死代码。
- 组装器只接收当前策略已获得的数据，不额外访问 DAO/Manager。

#### 修改 `torn/manager/faction/crime/msg/TornFactionOcMsgManager.java`

1. 构造器注入新增 `OcTableDocumentAssembler`、`TableImageRenderer`、`OcImageStatusResolver` 与 `OcImageTitleFormatter` 所需组件；不得使用 field injection。
2. `buildOcTable`、`buildRecommendTable` 在各自入口仅获取一次 `LocalDateTime now`，贯穿标题、团队状态与文档组装。
3. 查询槽位后用 `ocId -> slotList` Map 预分组；推荐路径同时用 `ocId -> oc` Map，消除当前反复 stream filter 的 O(N×M) 扫描。
4. 保留当前用户批量查询，不新增按成员查询。
5. 不再构造 `TableDataBO`、`TableImageUtils.TableConfig` 或 `java.awt.Color`；删除只服务旧 OC Java2D 路径的私有高亮方法。
6. 输出仍为 Base64 字符串，保证 `OcQueryStrategyImpl`、`OcRecommendStrategyImpl`、`TornOcCompleteNoticeService` 的发送调用签名不变。
7. 建议保留 package-private 的“带 `now` 数据组装方法”，只给同包测试使用，避免通过 mock Clock 或系统时间写脆弱测试。

#### 不修改、仅经公共 Manager 自动迁移的策略

```text
napcat/strategy/faction/crime/OcQueryStrategyImpl.java
napcat/strategy/faction/crime/OcRecommendStrategyImpl.java
napcat/strategy/faction/crime/OcAssignStrategyImpl.java
```

它们已只依赖 `TornFactionOcMsgManager` 的 Base64 返回契约；OC 分配同样复用 `buildRecommendTable(...)`。没有新增参数、命令或发送协议的必要。只有编译发现构造器接线确实需要时才做最小注入适配。

---

## 7. 浏览器运行时、安全和性能契约

### 7.1 生命周期

```text
Spring 启动
  -> PlaywrightBrowserManager 创建 Playwright + Chromium Browser
  -> 失败：应用启动失败，不接受半可用状态

一次图片请求
  -> 公平 Semaphore 尝试获取（最多等待 acquireTimeout）
  -> 创建独立 BrowserContext / Page
  -> 设置受控内存 HTML
  -> 路由阻断一切外部请求
  -> screenshot 表格根元素
  -> finally 关闭 Context、释放 Semaphore

Spring 关闭
  -> 关闭 Browser
  -> 关闭 Playwright
```

- 仅一个 Browser、仅一个并发页面；当前单实例部署下无需分布式锁。
- Page、Context 必须 request-scoped，不能跨消息共享。
- 禁止每次图片启动 Chromium，禁止启动后台浏览器池超过 1。

### 7.2 故障边界

| 场景 | 必须行为 | 禁止行为 |
|---|---|---|
| Browser 未启动 | 应用启动失败 | 启动后静默等首个图片失败 |
| 等待渲染许可超时 | 抛出明确 BizException 并记录安全日志 | 无限等待、创建第二个 Browser |
| 页面/截图超时 | 关闭该 Context，抛明确异常 | 复用异常 Page |
| Browser 崩溃 | 当前请求内受控重建一次 | 无限重试或每次失败新开浏览器 |
| 重建仍失败 | 明确图片失败，保留原异常因果 | 静默丢消息或无提示走旧 Java2D |
| 外部 URL/资源请求 | 路由 abort | 放行网络/CDN/本地文件 |

默认不做双渲染降级。若浏览器不可用，显式失败比悄然返回无 Emoji 的旧 Java2D 图片更可观测；发布回滚通过切回 1.5.3 镜像完成。

### 7.3 性能目标

- 文档组装：O(OC 数 + 槽位数 + 用户数)，无额外 DAO/API。
- 同一 OC 图片仅一次批量加载 OC 槽位和用户映射。
- 常驻 Browser 后，OC 图片渲染 P95 不高于 1 秒。
- 连续 100 次渲染不出现 BrowserContext 泄漏、许可未释放或 Chromium 子进程残留。
- 实际资源值（镜像增量、启动后容器内存、100 次后的容器内存）必须作为发布证据；未经实测不得声称 NAS 可接受。

---

## 8. 实施顺序

### 阶段 A：版本与独立 Spike

1. `pom.xml` 升至 `1.6.0`，增加固定 Playwright 依赖。
2. Dockerfile 添加固定 Chromium、字体、许可证和浏览器路径；不接入业务入口。
3. 新建通用 `document` / `render` / `render.html` 包及配置属性。
4. 用静态 `TableDocument` 测试夹具渲染一张含中文、四种 Emoji、跨列、长文本、推荐与灰化样式的 PNG。
5. 完成 Docker 内性能、资源、网络隔离和崩溃恢复验证。

**阶段 A 未通过，禁止修改 OC Manager 生产路径。**

### 阶段 B：恢复 OC 领域状态与快照

1. 添加 OC 槽位快照字段、API 映射、同步逻辑、Liquibase。
2. 添加 Resolver、Formatter 和共享执行时间计算器。
3. 先完成领域测试、同步测试和现有完成通知公式复用。
4. 此阶段仍不切换 OC 图片渲染入口。

### 阶段 C：OC 文档组装与入口迁移

1. 实现 `OcTableDocumentAssembler`。
2. 将 `TornFactionOcMsgManager` 两类图片入口切至新 renderer。
3. 保持策略与 NapCat 发送 API 不变。
4. 迁移 `OcMemberStrategyImpl` 和 `OcRateQueryStrategyImpl` 的直接 Java2D 表格路径；当前 OC、推荐、分配、完成通知均通过公共 Manager 自动切换。
5. 生成当前 OC、推荐/分配、可加入成员、用户成功率四类代表性图片，进行人工视觉验收。

### 阶段 D：发布准备

1. 完成 Maven、Docker、容器和性能证据。
2. 审核依赖/字体许可证与版本。
3. 仅在实际 `1.6.0` 镜像构建完成后，单独调整发布 tag/compose 工件。

---

## 9. 测试方案（收敛）

### 9.1 保留的主要测试层级

| 规则 | 唯一主测试层 | 代表性场景 |
|---|---|---|
| OC 标题时间状态机 | `OcImageTitleFormatterTest` | null、Recruiting 停转/24h、Planning 执行/空转/过期 |
| 槽位 Emoji 优先级 | `OcImageStatusResolverTest` | 空槽、空转、缺道具、完成、准备中、非法进度 |
| 快照同步 | `TornFactionOcSlotManagerTest` | 写入缺道具、恢复可用、无需求/无人清理、未知不误写 false |
| 当前 OC 状态表组装 | `OcTableDocumentAssemblerTest` | 一个普通块 + 一个推荐块，验证单元格顺序、样式、唯一 Emoji、跨列；分配复用推荐入口，不重复建矩阵 |
| OC 历史/候选表组装 | `OcHistoryTableDocumentAssemblerTest` | 可加入成员六列、成功率按 OC 跨列分组和既有排序/筛选输入 |
| HTML 安全映射 | `HtmlTableMarkupRendererTest` | `<>&"'` escape、固定 class/span、无外链属性 |
| 浏览器真实运行 | `HtmlTableImageRendererIntegrationTest` | Docker 内中文/Emoji、连续 100 次、网络拦截、一次重建 |
| OC 公共接线 | `TornFactionOcMsgManagerTest` | 当前、推荐/分配路径均委托新 renderer；固定 now、批量 DAO 边界 |
| OC 直接策略接线 | `OcMemberStrategyImplTest`、`OcRateQueryStrategyImplTest` | 各保留一条图像消息接线和已有筛选/空结果主路径；不复制渲染细节 |

### 9.2 测试文件

新增：

```text
src/test/java/pn/torn/goldeneye/utils/image/document/TableDocumentTest.java
src/test/java/pn/torn/goldeneye/utils/image/render/html/HtmlTableMarkupRendererTest.java
src/test/java/pn/torn/goldeneye/utils/image/render/html/HtmlTableImageRendererIntegrationTest.java
src/test/java/pn/torn/goldeneye/torn/service/faction/oc/image/OcImageStatusResolverTest.java
src/test/java/pn/torn/goldeneye/torn/service/faction/oc/image/OcImageTitleFormatterTest.java
src/test/java/pn/torn/goldeneye/torn/service/faction/oc/image/OcTableDocumentAssemblerTest.java
src/test/java/pn/torn/goldeneye/torn/service/faction/oc/image/OcHistoryTableDocumentAssemblerTest.java
src/test/java/pn/torn/goldeneye/torn/service/faction/oc/OcPreparationTimeCalculatorTest.java
```

修改：

```text
src/test/java/pn/torn/goldeneye/torn/manager/faction/crime/TornFactionOcSlotManagerTest.java
src/test/java/pn/torn/goldeneye/torn/manager/faction/crime/msg/TornFactionOcMsgManagerTest.java
src/test/java/pn/torn/goldeneye/torn/service/faction/oc/TornOcCompleteNoticeServiceTest.java
src/test/java/pn/torn/goldeneye/napcat/strategy/faction/crime/OcMemberStrategyImplTest.java
src/test/java/pn/torn/goldeneye/napcat/strategy/faction/crime/OcRateQueryStrategyImplTest.java
```

### 9.3 禁止的测试膨胀

- 不为 `OcQueryStrategyImpl`、`OcRecommendStrategyImpl`、`OcAssignStrategyImpl`、`TornOcCompleteNoticeService` 分别复制 Emoji/标题状态机矩阵；分配复用推荐公共入口，只保留已有策略接线回归。
- `OcMemberStrategyImpl`、`OcRateQueryStrategyImpl` 不复制文档模型或 CSS/浏览器测试，只保留各自既有业务筛选的接线验证。
- 不用 OCR 或逐像素全图快照断言业务文案。
- 不建立真实 PostgreSQL 大型并发矩阵；本轮唯一真实数据库必要性是 Liquibase/DO/同步链字段兼容，按项目共享库约定精确清理。
- 不为所有 23 个旧 `TableImageUtils` 调用方增加回归测试或强制迁移。
- 不通过读取源码、CSS 或 YAML 字符串断言替代真实渲染/业务行为验证。

### 9.4 命令与证据

开发完成必须串行执行：

```bash
JAVA_HOME="C:\Program Files\Java\jdk-21" mvn.cmd -q -DskipTests compile
JAVA_HOME="C:\Program Files\Java\jdk-21" mvn.cmd -q -Dtest=TableDocumentTest,HtmlTableMarkupRendererTest,OcImageStatusResolverTest,OcImageTitleFormatterTest,OcTableDocumentAssemblerTest,OcHistoryTableDocumentAssemblerTest,OcPreparationTimeCalculatorTest,TornFactionOcSlotManagerTest,TornFactionOcMsgManagerTest,TornOcCompleteNoticeServiceTest,OcMemberStrategyImplTest,OcRateQueryStrategyImplTest test
git diff --check
docker build -f build/Dockerfile -t golden-eye:1.6.0 .
```

浏览器集成测试必须在最终 Linux Docker 镜像中执行或调用该镜像内运行的验证入口。Windows 本机 JDK 或截图只能辅助开发，不能作为 Emoji/Chromium 发布证据。

开发人员**只执行上述聚焦测试**，不得自行执行全量 Maven 测试：仓库存在会触发真实 Torn API 请求的测试，重复全量执行会额外消耗 API Key。全量 Maven 测试由架构验收人员在开发完成、Docker 与聚焦验证均通过后统一串行执行，原则上只执行一次；若全量失败，先按失败调用链判断是否与本轮有关，未确认根因前不得重复消耗 Key 重跑。任何全量执行均不能与其他 Maven 任务并行共享 `target/`。

---

## 10. Review 阻断项

以下任一项为 P0/P1，禁止进入 1.6.0 发布：

1. 任一迁移后的 OC 图片请求新增 Torn API 调用，或出现 DAO N+1。
2. 业务层、OC Manager 或策略层直接拼接 HTML/CSS/URL。
3. 浏览器页面可访问外部网络、`file:` 或未受控资源。
4. 应用运行时下载 Chromium，或依赖 NAS/宿主机字体。
5. 每次渲染启动 Browser，或 Browser/Context/信号量在异常路径不关闭/不释放。
6. Browser 失败时无限重试、静默丢图片或无证据降级到旧 Java2D 图。
7. `TableDocument` 依赖 Playwright、`java.awt`、`TableImageUtils` 或 OC 领域模型。
8. 旧 Java2D `TableDataBO` 被当作新 HTML 渲染模型继续传播。
9. Emoji 仍显示方块/空白，或只凭字符串包含、Java2D 字体探针宣称可见。
10. 标题和团队状态颜色未使用相同固定 `now`。
11. 空槽显示 Emoji、非法进度被误判、未知道具快照被写/解释为缺道具、同成员显示多个 Emoji。
12. 修改已执行 Liquibase changeSet、伪造历史 item snapshot 或为本功能增加无收益索引。
13. 直接 cherry-pick 旧 Java2D OC 图片美化实现，造成两套状态/渲染逻辑并存。
14. 未提供真实 Docker 中的 Emoji、中文、100 次稳定性、资源与网络隔离证据。
15. `pom.xml` 或 `build/docker-compose.yml` 的最终版本标识不是 `1.6.0`，或开发提交覆盖/改写了用户预先设置的版本标识。
16. Docker 镜像使用 `golden-eye:1.6.0-rc1`、`latest` 或任何非 `golden-eye:1.6.0` 标签。

以下属于 P2/P3，记录但不自动扩大本轮范围：

- 第二套主题、深色模式；
- 其他表格的迁移建议；
- 远程头像/二维码/图表库；
- 对旧 `TableImageUtils` 的全面清理。

---

## 11. 交付与验收清单

开发完成后必须提供：

1. 当前提交范围及逐文件职责说明。
2. `pom.xml` 中 Playwright 精确版本、Chromium 精确版本/安装路径、Emoji 字体名称/版本/许可证说明。
3. Docker 镜像构建日志、镜像 digest/大小、容器 JDK、字体目录和浏览器可执行性验证。
4. 四类代表性 PNG 原图：当前 OC、推荐/分配、可加入成员、用户成功率；当前 OC 状态类图片必须显示中文及 `💤 / ⏳ / ✅ / ⚠️`，供人工视觉验收。
5. 常驻 Browser 连续 100 次渲染的成功数、P50/P95、启动后/执行后内存实测，及一次 Browser 重建结果。
6. 网络拦截测试证明没有模板外部请求。
7. OC 状态机、快照清理、文档组装、HTML escape、公共接线的聚焦测试结果；明确 testCompile 与 Surefire 实际执行数。
8. Liquibase changeSet ID、新增列的实际结构与 remarks 验证；若执行真实共享库测试，说明精确物理清理结果。
9. Maven compile、聚焦测试、`git diff --check` 的真实命令与退出结果。
10. 明确列出未迁移的非 OC Java2D 图片调用方与理由；不能将任何 OC 表格图片遗漏为未迁移项。
11. 证明 `OcQueryStrategyImpl`、`OcRecommendStrategyImpl`、`OcAssignStrategyImpl`、`OcMemberStrategyImpl`、`OcRateQueryStrategyImpl`、`TornOcCompleteNoticeService` 的消息格式/发送协议保持不变。
12. 证明 `pom.xml` 与 `build/docker-compose.yml` 均标识 `1.6.0`，Docker 本地构建标签为 `golden-eye:1.6.0`。
13. 不存在本章 P0/P1 阻断项。

满足以上条件后停止第一轮。后续图片迁移、主题、多媒体与图表能力必须以独立技术方案授权，禁止随本轮继续扩张。
