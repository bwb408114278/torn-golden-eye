# 1.7.0 指令检索纠错框架 + 手册图片化技术方案

> 方案类文件，只记录决策与契约，不含实现。视觉原型：`.ai/design/bot-doc-preview.html`；实测 PNG：`.ai/design/01-`、`02-`（参考 `.ai/design/99-`）。

## 1. 文档定位

- **目标版本：** `1.7.0`（当前 `pom.xml` 为 `1.6.5`）
- **范围：** 指令发现链路（手册分档 / 关键词检索 / 打错纠错）+ 手册图片化。
- **风险等级：** **L2**。不涉及金额、资金守恒、数据库写入、Schema、事务与并发；触及群聊/私聊两条入口、`BaseMsgStrategy` 契约与新增渲染主题，必须有入口回归。
- **不在本轮：** 自然语言路由、业务策略查询逻辑变更、渲染平台受控模型扩展。

## 2. 已确认决策

| # | 决策 |
|---|---|
| 1 | 检索与纠错做成**框架级**：`BotCommandRegistry` 作为指令视图唯一事实源 |
| 2 | `getCommandCategory()` 设 **`abstract`**，编译期强制全部策略显式声明分类 |
| 3 | 分类名：用户 / 资产 / OC / RW / Stock / 赛车 / VIP / 管理（股市、OC、RW 不带中文；PC 叫赛车；物资改资产） |
| 4 | 「拍卖历史」归**资产**；「绑Key」归**用户**，且是全部指令**第一条** |
| 5 | 图片宽度 **640px**、`device-scale-factor=1`、**保留说明列** |
| 6 | **一指令一图**：`g#手册`、`g#管理手册`、私聊 `g#手册` 各出各自的图，不合并 |
| 7 | 打错纠错输出**纯文本** |
| 8 | 文案统一：`g#指令` 在前、`g#` 在后，并写明 `g#` ＝ `g#手册` |
| 9 | **不设图片体积门禁**：始终发图，只有渲染抛异常才降级文本 |
| 10 | **不提供**强制文本档口子 |
| 11 | 分类顺序：图①（普通菜单）用户 → OC → RW → 资产 → 赛车 → Stock；图②（管理菜单）资产 → OC → RW → Stock → 管理 |
| 12 | 「拍卖记录同步」归**资产**（管理菜单），置于该分类末位 |

## 3. 现状事实（代码坐标）

| 事实 | 位置 |
|---|---|
| 手册把所有可见策略拼成一个 `StringBuilder`，一次性返回单条长文本 | `DocStrategyImpl.handle`、`ManageDocStrategyImpl.handle`、`PrivateDocStrategyImpl.handle` |
| 未匹配指令**静默丢弃**，零反馈 | `GroupMessageHandler:61-63`、`PrivateMessageHandler:55-57` |
| 路由为 `equalsIgnoreCase`，不容空格、无归一化、无别名 | `GroupMessageHandler.findStrategy`、`PrivateMessageHandler.findStrategy` |
| 分类信息只以注释存在于常量类，无代码读取 | `BotCommands.java` 的分组注释 |
| 已有模糊匹配 | `utils/StringFuzzyMatchUtils` |
| 已有 HTML/CSS + Playwright Chromium 渲染平台 | `TableDocument` / `HtmlTableMarkupRenderer` / `HtmlTableImageRenderer` / `TableThemeEnum` |
| 渲染视口 1600；图片实际宽度由主题 CSS `.table-image { width }` 决定 | `TableImageRenderProperty`、`pc-race-table.css`(1120px) |

### 3.1 可见性模型（逐条从代码推导）

| 机制 | 代码 | 影响 |
|---|---|---|
| 超管专属 | `isNeedSa()`，含 `BaseStockHistoryRangeStrategy` 等基类覆写 | 12 条 |
| 角色专属 | `getRoleType()`：LEADER / OC_COMMANDER / WAR_COMMANDER / QUARTERMASTER | 11 条 |
| 群白名单 | `BaseRwStrategy.getCustomGroupId()`：Pn 群 + CCRC + SH + HP + BSU | 6 条 RW |
| 私聊指令 | `BaseVipMsgStrategy extends BasePrivateMsgStrategy` | 提醒设置/取消/暂停/恢复、Stock分析 不进群手册 |
| 普通菜单**不看超管身份** | `DocStrategyImpl` 的过滤条件是 `!isNeedSa()` 且 `getRoleType() == null`，没有 `isSa` 分支 | 超管发 `g#手册` 与普通成员同图 |
| 管理菜单**不看普通指令** | `ManageDocStrategyImpl.checkStrategyRole` 对 `roleType == null && !isNeedSa` 一律返回 false | 两本菜单互补、不重叠 |

### 3.2 三个手册指令 × 各自菜单的真实条数

| 指令 | 菜单 | 谁发 | 条数 | 判定 |
|---|---|---|---:|---|
| `g#手册`（群） | 普通指令菜单 | PN 群成员 | **28** | `!isNeedSa` ∩ `getRoleType()==null` ∩ 群匹配 |
| `g#手册`（群） | 普通指令菜单 | PTA 群成员 | 22 | 同上，且群不在 RW 白名单 |
| `g#管理手册`（群） | 管理指令菜单 | 超管 | **23** | needSa ∪ 全部角色指令 |
| `g#管理手册`（群） | 管理指令菜单 | 帮派 Leader | 11 | 全部角色指令 |
| `g#管理手册`（群） | 管理指令菜单 | 战争指挥官 | 4 | 仅 WAR_COMMANDER |
| `g#管理手册`（群） | 管理指令菜单 | OC 指挥官 | 5 | 仅 OC_COMMANDER |
| `g#管理手册`（群） | 管理指令菜单 | 军需官 | 1 | 仅 QUARTERMASTER |
| `g#手册`（私聊） | 私聊菜单 | 任何人 | 6 | 全部 `BasePrivateMsgStrategy` |

**单张图的规模上限就是 28 行（普通菜单）与 23 行（管理菜单）**，不存在更大的图。

> 勘误：本方案上一版把「超管」当成与「群」并列的一个图片维度，并给出「两本手册合并 51 条」的包络，那是错的——它是把两个独立指令的菜单混在一起。已改为上面的一指令一图口径。

`SmthMsgStrategy` 的注释是「Pn群消息策略」，即需求里说的 **PN 群**；活跃度、活跃度对比、战力增长、师父排队属于该体系。

### 3.3 策略参数传递契约（冻结，2026-10-04 实测复核）

| 事实 | 代码坐标 |
|---|---|
| 派发层按 `commandText.split("#", 3)` 拆出 `msgArray`，`msgArray[1]` 是**指令名**，`msgArray[2]` 是**其后全部文本** | `BotMessageDispatcher:57` |
| 交给策略的参数**只有 `msgArray[2]`**（`msgArray.length > 2 ? msgArray[2] : ""`），**不含指令名** | `BaseMessageHandler#resolveParam` → `GroupMessageHandler#buildReplyMsg` / `PrivateMessageHandler` |
| 用户输入形态恒为 `g#<指令>#<参数1>#<参数2>…`（群）/私聊同构 | `QqCommandMessageParser`（群要求以 `g#` 开头） |

**契约（写新指令必须遵守）**：

1. 策略 `handle(groupId, sender, msg)` 的 `msg` **只含参数段**；按「含指令名」的整条文本解析必然错；
2. 参数个数按**参数段数**判定：两参数 = `"<p1>#<p2>".split("#").length == 2`；
3. Java 语义警示：`split(sep, 正数limit)` 的**最后一段包含剩余全部内容**（不丢弃），与 JavaScript 的 `split(sep, limit)`（超出部分直接丢弃）**不同**，勿跨语言推断；
4. 新增带参指令必须补「**纯参数形态**」单测（`handle(..., "p1#p2")`），并建议补一个走 `BotMessageDispatcher.dispatch(...)` 的端到端用例；只喂「指令名#参数」的用例测的是错误契约。

**对照组**：正确实现 `BaseStockHistoryRangeStrategy`（2 段）、`AuctionSyncStrategyImpl`（≥2 段）、`StocksFeatureBuildStrategyImpl`（2 段）；派发层断言见 `BotMessageDispatcherTest`（`g#战力增长#12345` → `{"g","战力增长","12345"}`）、端到端见 `OcBenefitAtHistoryMonthEndToEndTest`。

**已发生的反例（2026-10-04）**：

| 指令 | 缺陷 | 后果 |
|---|---|---|
| `回补Stock月度风格`（**已下线删除**） | `StockMonthlyStyleBackfillStrategyImpl#resolveMonths` 要求 `split("#").length == 3` 并取 `parts[1]`/`parts[2]` | 生产环境**永远**回「月份参数无效」，一度阻断月度数据验收；该指令为一次性生命周期，2026-10-04 回补完成并验收全绿后**随指令整体删除**，缺陷不再修复（保留为契约反例） |
| `预填Stockα日线`（**未修复，P3 后续建议**） | `StockAlphaDailyPrefillStrategyImpl#resolveEndDate` 以 `!msg.contains("#")` 判无参 | 显式日期参数被**静默忽略**（退化为最近已结束自然日），非法日期不被拒绝；1.6.x 既有、不属 1.8.0 范围，未纳入本批修复 |

> 说明：`回补Stock月度风格` 的 P1 未修复是用户决策（该指令为一次性生命周期，2026-10-04 直接删除代码而非修复）；其参数解析缺陷仅作契约反例留档，原热修工作单已按生命周期删除。

---

## 4. 决策一：检索与纠错框架化

### 4.1 为什么是框架级而不是工具类

1. **四处必须共享同一份索引。** 路由、手册可见性、关键词检索、纠错候选各自实现必然漂移，会出现「纠错建议的指令发出去却匹配不上」。工具类只能复用算法，保证不了同一集合、同一过滤、同一归一化。
2. **注入形态本来就已是框架级。** 现有 `List<BaseGroupMsgStrategy>` 与 `getBeansOfType` 已是自动收集，注册表只是把结果索引化，改动同构。
3. **新策略零注册成本**，继承基类 + 覆写方法即接入。

### 4.2 分类枚举（定稿）

```java
public enum BotCommandCategoryEnum {
    USER("用户", 10),
    ASSET("资产", 20),
    OC("OC", 30),
    RW("RW", 40),
    STOCK("Stock", 50),
    RACING("赛车", 60),
    VIP("VIP", 70),
    MANAGE("管理", 80);

    private final String displayName;
    private final int order;
}
```

- 不设 `OTHER`：`getCommandCategory()` 为 `abstract`，编译器保证不漏项。
- `VIP` 只服务私聊菜单；群菜单分类为 用户 / 资产 / OC / RW / Stock / 赛车 / 管理。
- `MANAGE` 只在管理菜单出现。
- **顺序与归属（已确认，分菜单各排各的）：**
  - 图① `g#手册`（PN 群 28 条）：用户(5) → OC(10) → RW(6) → 资产(3) → 赛车(2) → Stock(2)
  - 图② `g#管理手册`（超管 23 条）：资产(4) → OC(5) → RW(6) → Stock(4) → 管理(4)
  - 「绑Key」→ 用户且全表第 1 条；「拍卖历史」→ 资产（普通菜单）；「拍卖记录同步」→ 资产（管理菜单，末位）。
  - 同名分类在两个菜单里装的是两套不同指令集合（RW / 资产 / OC / Stock 均如此），顺序不能跨菜单套用。

### 4.3 契约变更

| 方法 | 语义 | 默认值 | 强制 |
|---|---|---|---|
| `getCommand()` / `getCommandDescription()` | 既有 | — | 既有抽象 |
| `getCommandCategory()` | 手册分类 | 无默认 | **是（abstract）** |
| `getCommandUsage()` | 参数格式与示例 | 空字符串 | 否 |
| `getCommandAliases()` | 别名，如「收益」指向「OC收益」 | `List.of()` | 否 |

**`BotCommandRegistry`（`@Component`）：**

- 注入全部策略，建立「归一化指令名 → 策略」「别名 → 策略」索引。
- 归一化：去首尾空白、去指令内部空格、大小写折叠、全角转半角。**现状多打一个空格就完全匹配不上，这是最高频的失败。**
- `resolve(String)` 替代现有两处 `findStrategy`；`suggest(String,int)` 出候选；`search(String,Predicate)` 供检索档。
- **可见性过滤留在调用方**（三个手册各自的口径不变），注册表不承担权限判断。

### 4.4 分档规则

| 输入 | 档位 | 输出 |
|---|---|---|
| `g#手册` / `g#`（等价） | 全览档 | 当前用户在当前群可见的指令（图片） |
| `g#管理手册` | 全览档 | 按 `checkStrategyRole` 得到的管理菜单（图片，与上表口径一致） |
| `g#手册 分类名` | 分类档 | 该类「指令 + 说明」（图片） |
| `g#指令 关键词` | 检索档 | 命中列表；**命中唯一时直接跳用法档** |
| `g#指令 指令名`（亦可用 `g#手册 指令名`） | 用法档 | 用途 / 格式 / 示例 / 权限 / 生效范围 / 支持@（图片） |
| 未识别指令 | 纠错档 | **纯文本**一条，最多 3 条候选 |

判定顺序：**精确指令 → 精确分类 → 关键词检索**。分类名与指令名冲突时指令优先。

### 4.5 底栏与纠错文案（定稿）

```text
g#指令 关键词 ｜ g#手册 分类名 ｜ g# = g#手册 = 全部指令
```

```text
没找到指令「OC收益榜n」

你是不是想找：
· g#OC收益榜 —— 帮派OC收益排行
· g#OC收益 —— 查询OC收益，例g#OC收益(#用户ID)(#yyyy-MM)

发送 g#指令 查看全部指令
```

`g#指令` 一律排在 `g#` 之前；相似度过低时只回最后一行，不硬猜；建议同一用户 10 秒内同原文只回一次。

---

## 5. 决策二：手册图片化

### 5.1 复用现有平台，只加一个主题

| 新增项 | 说明 |
|---|---|
| `TableThemeEnum.BOT_DOC("bot-doc", "/table-image/bot-doc.css")` | 主题注册，未注册类型仍快速失败 |
| `src/main/resources/table-image/bot-doc.css` | 主题层，复用 `table-base.css` |
| `BotDocDocumentAssembler` | 档位 + 指令视图 → `TableDocument`（宽度 640） |
| `BotDocStrategyImpl` | 只做参数解析与档位分派（三个手册指令各自产出自己的可见集后交给它渲染） |

### 5.2 布局

- **三列 = 分类(合并) | 指令 | 说明**，说明列保留（用户确认）。
- 分类列由汇编器发一个 `TableCell(style=SECTION, rowSpan=组内条数)`，后续行不再重复分类单元格；`HtmlTableMarkupRenderer` 已支持输出 `rowspan`，渲染平台零改动。
- 已实测验证：分类列背景 `#EEF1FB` 从该组首行连续贯穿到末行，合并生效。
- **每张图的表格列数恒定**，便于用一套主题 CSS 覆盖三个菜单。

### 5.3 两个必须绕开的 CSS 陷阱

1. **rowspan 会让 `nth-child` 列规则错位。** 每组首行有 3 个 `td`、其余行只有 2 个；用列序号定位会导致每组第一条指令样式孤立。**必须按语义 class 上色。**
2. **脱离 `table-base.css` 单独内联主题会走样（实测）。** 微软雅黑声明在基础层，只带主题 CSS 会退回默认字体：同一张 28 条图从 112.8 KB 涨到 200.6 KB、高度多 8px。复现预览必须「基础层 + 主题层」一起带上。
3. **主题层选择器特异度高于基础层溢出策略类。** `.table-image table td.cell-body`（0,1,3）压过基础层 `.overflow-wrap`（0,1,0）。主题层一旦给 `cell-*` 设置 `white-space`，`overflow-wrap` 会被静默吃掉（原型第一版即如此）。**主题层禁止设置 `white-space`。**

说明列复用 `cell-footer` 语义、底栏用 `[colspan]` 属性选择器区分，属零平台改动。若要语义干净可新增 `TableCellStyleEnum.CELL_NOTE`，本轮不做。

### 5.4 实测体积（真实 PNG，Edge 无头渲染，640 宽、带说明列、scale=1）

| 图 | 像素尺寸 | PNG | base64 |
|---|---|---|---|
| **`g#手册` PN 群 · 28 条**（用户/OC/RW/资产/赛车/Stock） | **640 × 907** | **112.8 KB** | 150 KB |
| **`g#管理手册` 超管 · 23 条**（资产/OC/RW/Stock/管理） | **640 × 758** | **85.3 KB** | 114 KB |
| （旧版对照）760 宽 · 38 条 | 760 × 1762 | 168.0 KB | 224 KB |
| （参考，不采用）双列无说明 · 28 条 | 640 × 532 | 40.4 KB | 54 KB |
| （参考，不采用）device-scale-factor=2 | 1520 × 3524 | 607.4 KB | 810 KB |

相比最初 760 宽版本：宽度 -16%、高度 -49%、体积 -33%。保留说明列的前提下，这就是当前口径的实际成本。

### 5.5 NapCat 发送评估

- 链路：Java 生成 PNG → Base64 → NapCat（WebSocket/HTTP）→ 上传腾讯 → 客户端。**base64 体积 = PNG × 1.37**，这是实际传输量。
- 实发的最重一张是普通菜单 113 KB（base64 151 KB），属于常规量级。
- **`device-scale-factor=2` 不使用**（810 KB）：上传与加载明显变慢，部分客户端二次压缩导致文字发虚。
- **不设体积门禁**：始终发图，只有渲染抛 `BizException` 时才降级为短文本目录。

### 5.6 体积实测方法（可复用于验收）

```powershell
& $edge --headless=new --disable-gpu --no-sandbox --hide-scrollbars `
  --user-data-dir=$ud --screenshot=$png --window-size=640,907 `
  --force-device-scale-factor=1 --default-background-color=FFFFFFFF $html
# --screenshot 只截视口，窗口高度必须等于内容真实高度；
# 再用 System.Drawing 读像素尺寸与文件体积
```

### 5.7 文本兜底

图片链路会抛 `BizException`。手册是高频入口，**必须文本降级**（目录 + 提示的短文本），这是本轮唯一的新增失败路径，需一条测试覆盖。

---

## 6. 明确不做

1. 不做 LLM 自然语言路由：误触发直接消耗 Torn API Key 与查询预算。
2. 不维护独立手册文档或常量表：手册必须从策略声明生成。
3. 不把长文本拆成多条消息连发。
4. 不扩展渲染平台受控内容模型（子串高亮、CELL_NOTE 均不在本轮）。
5. 不做图片体积门禁，不提供强制文本档。
6. 不顺手改动任何业务策略的查询逻辑与文案口径。

## 7. 验证与验收（L2）

- **注册表单测：** 归一化（空格/大小写/全角）、别名命中、未命中、候选排序、可见性不泄漏。
- **纠错单测：** 未识别指令恰好一条文本回复；无低相似候选不硬猜；节流生效。
- **汇编器单测：** 三个菜单的 `documentType`、行数、`rowSpan` 值与受控 class 正确；**断言行内 colSpan 之和与 rowspan 覆盖关系一致**。
- **可见集单测：** PN 群 28 条、PTA 群 22 条、超管管理菜单 23 条、Leader 11 条、军需官 1 条；超管发 `g#手册` 与普通成员结果一致。
- **降级单测：** 渲染抛异常时回退文本，且只有这一条路径产生文本。
- **入口回归：** 群聊与私聊，覆盖「精确命中 / 检索命中 / 未命中」三种走向。
- **渲染集成：** 复用 `HtmlTableImageRendererIntegrationTest` 路径渲染三个菜单，人工看图。

## 8. 状态

- 分类顺序、归属与命名均已定稿；两张图已按最终顺序重渲为 `.ai/design/01-menu-normal-pn-640x907-113KB.png` 与 `.ai/design/02-menu-manage-sa-640x758-85KB.png`。
- 渲染源：`.ai/design/render-menu-normal.html` / `render-menu-manage.html`；预览：`.ai/design/bot-doc-preview.html`。
- 本轮仅维护方案与原型文件，编码尚未开始。
