# OC 推荐油猴插件技术方案（客户端）

## 1. 目标与非目标

**目标**：在 Torn 的 OC 页面按后端返回的结论做"只读呈现"——推荐项排到最前、非推荐岗位的加入按钮禁用、状态提示。

**非目标**：任何自动化操作（自动点加入、自动刷新、定时抢位）。触发页面写操作会导致封号，本方案在代码层面就不实现点击转发。

**边界**：插件不含任何业务判定。推荐、比较、进度限制、大锅饭规则全部由后端返回 `status` 与列表，插件只做 switch 渲染。

## 2. 交付物与工程形态

- 路径：`docs/userscript/torn-golden-eye.user.js`（与后端契约同仓版本化，内部分发）
- 形态：**单文件、无构建链**。理由：Torn PDA 的脚本引擎对多文件与模块加载支持不确定，单文件是唯一稳妥形态；内部发行也不需要 tree-shaking。
- 内部按命名空间分区（下方第 4 节），每个分区只暴露必要方法，禁止跨区直接访问内部状态。

## 3. 运行环境与元信息

```javascript
// ==UserScript==
// @name         金眼OC推荐
// @namespace    golden-eye
// @version      1.0.0
// @match        https://www.torn.com/factions.php*
// @run-at       document-idle
// @grant        GM_xmlhttpRequest
// @grant        GM_getValue
// @grant        GM_setValue
// @connect      <后端域名>
// ==/UserScript==
```

- 桌面端 Tampermonkey 走 `GM_xmlhttpRequest`，不受 CORS 限制；
- Torn PDA 的 WebView 若不支持该 API，自动退回页面 `fetch`（需后端 CORS 白名单，已在接口方案覆盖）；
- 两条路径共用同一 `Net` 接口，调用方不感知差异。

## 4. 模块划分（单文件内的六个分区）

| 分区 | 职责 | 对外方法 |
| --- | --- | --- |
| `Store` | 存储适配：`GM_getValue` 优先，退回 `localStorage` | `get(key, def)` / `set(key, val)` |
| `Net` | 网络适配：GM / fetch 双实现，统一超时与错误 | `request({method, path, body})` |
| `Api` | 后端契约封装：鉴权头、错误码到用户提示的映射 | `ping()` / `recommend()` / `observations(list)` / `bind(key)` |
| `Dom` | 页面锚点与结构读取：OC 卡片、槽位、成功率、按钮 | `readPage()` / `apply(recommend)` / `restore()` |
| `Ui` | 浮层、角标、弹窗、开关面板（纯渲染，不含判定） | `renderStatus()` / `showAgreement()` / `toast()` |
| `App` | 编排：开关状态、拉取、渲染、异常降级、路由变化 | `start()` / `toggle(on)` / `refresh()` |

## 5. 页面锚点（来自真实页面快照）

| 目标 | 选择器 | 备注 |
| --- | --- | --- |
| OC 列表容器 | `.tt-oc2-list` | 原生类名，不带哈希，主锚点 |
| 单个 OC 卡片 | `.tt-oc2-list > div[data-oc-id]` | `data-oc-id` 稳定 |
| OC 名称 | `[class^="panelTitle"]` | |
| 槽位外层 | `[class^="wrapper"]`（在卡片内） | 空位额外带 `waitingJoin` |
| 岗位名 | `button[class^="slotHeader"] > span[class^="title"]` | 文本形如 `Muscle #2` |
| 成功率 | `div[class^="successChance"]` | 纯数字，空位=本人成功率，有人=该成员成功率 |
| 空位判定 | `[class*="waitingJoin"]` | 存在即为空位 |
| 加入按钮 | `[class*="joinButton"] button` | 文本 `Join` |
| 在位成员 | `[class^="badgeContainer"] span[class^="textName"]` | |
| 当前用户 | `window.topBannerInitData.user.data.userID` | 仅用于定位"我的岗位"，不参与鉴权 |

**选择器纪律**：只用"可读前缀 + 属性 + 文本"匹配，禁止依赖 `___xxxxx` 哈希。启动时执行一次自检——主锚点失配即判定页面结构已变，**自动关闭功能并提示一次**，绝不带着错误假设改页面。

## 6. 数据流

```
启动 → 读开关（默认关）
  → 关：直接返回，不注入任何 DOM
  → 开：readPage() 取本页 ocId/岗位/成功率
      → Api.recommend()
          → 成功：apply() 排序 + 禁用 + 状态角标；采集空位成功率上报
          → 失败：restore() 恢复原页面，浮层显示一次错误，进入 5 分钟冷却
```

## 7. 渲染规则

### 7.1 已知状态与表现

| status | 列表顺序 | 按钮 | 提示 |
| --- | --- | --- | --- |
| RECOMMEND_AVAILABLE | 推荐 OC 置顶，其余保持原顺序 | 仅推荐岗位可点 | "建议换到 X（可提升）" |
| ALREADY_BEST | 保持原顺序 | 推荐为空，全部保持：原逻辑 | "当前岗位已是最佳" |
| JOINED_WITH_PROGRESS | 保持原顺序 | 仅同队推荐岗位可点 | "有进度，仅建议队内换位" |
| DISABLED_OC | 保持原顺序 | 全禁 | "当前 OC 已被禁用" |
| PASS_RATE_INSUFFICIENT | 推荐置顶 | 仅推荐岗位可点 | 展示实测/要求成功率 |
| NO_VACANT | 保持原顺序 | 保持原逻辑 | "暂无空缺" |
| NOT_BOUND / FACTION_NOT_ENABLED | 保持原顺序 | 保持原逻辑 | 引导绑定/未开通 |

### 7.2 排序实现

用 CSS `order` 改视觉顺序，**不移动 DOM 节点**：React 管理的节点被物理搬移后可能被下一次渲染还原，导致状态错乱。给容器加 `display:flex;flex-direction:column`，推荐卡片设负值 `order`。`restore()` 只需移除该 class。

### 7.3 禁用实现

- 对非推荐槽位的加入按钮：`disabled` + `pointer-events:none` + 半透明；
- 保留查看、退出、历史等只读操作，不禁用导航；
- 所有改动打上 `data-ge-injected` 标记，`restore()` 按标记回滚；
- 禁止在代码中出现任何模拟点击、表单提交、`element.click()`。

### 7.4 幂等与重渲染

`MutationObserver` 监听 `.tt-oc2-list` 子树，300ms 防抖；每次渲染前先 `restore()` 再 `apply()`，避免重复叠加角标。路由变化（`hashchange`）触发一次完整刷新。

## 8. 鉴权与绑定

1. 首次使用：读 `localStorage.getItem("APIKey")`（冰蛙约定，明文 16 位）；
2. 命中则弹一次说明（讲清用途、会用于数据建设），用户确认后使用；值不符合 `^[A-Za-z0-9]{16}$` 则不提示，直接走手填；
3. 未命中：手填输入框 + 同一份说明；
4. Key 存 `Store`（`GM_setValue` 优先），展示一律掩码；
5. 401/403/409 分别映射到"去绑定/级别不足或未开通/已绑定"，不展示后端原始信息。

一次性说明只在首次出现，记录 `agreementVersion` 与确认时间；版本升级后才再次弹出。

## 9. 观测上报

- 只采集**空位**（`waitingJoin`）上显示的成功率，即"我本人对该岗位的成功率"；
- 有人岗位不采集（后端自己同步）；
- 客户端先去重：同 `ocId+position` 值未变化则不上报；单次最多 100 条；
- 上报失败不重试、不提示，静默计入下次批量。

## 10. 开关与 fail-safe

- 默认关闭；开关状态存 `Store`；
- 关闭时脚本不做任何 DOM 改动、不发请求；
- 接口超时 3 秒、错误码非 0、页面结构自检失败 → **立即 restore + 自动关闭开关**，避免把用户困在异常状态；
- 任何异常都不得抛出到页面全局，统一 `try/catch` 收口在 `App`。

## 11. 版本与更新

- 启动调用 `/api/v1/system/ping`（可跳过鉴权）；
- 若当前版本低于 `minClientVersion`，浮层提示升级并停止渲染；
- 内部分发：`@updateURL`/`@downloadURL` 指向内网静态地址，版本号与仓库文件同步。

## 12. 待补资料（实现前置）

1. 一段录屏：点 Join 前后页面 DOM 变化、切 tab 后重渲染表现；
2. Torn PDA 实测：是否支持 `GM_xmlhttpRequest`（不支持则自动走 fetch 路径）；
3. 后端域名与证书（配置 `@connect` 与 CORS 白名单）。

## 13. 验收清单

1. 关闭开关时页面与未安装插件的表现完全一致；
2. 打开后推荐 OC 置顶、非推荐岗位不可点、退出页面后无残留样式；
3. 后端 403 时按钮全部恢复可点并提示一次；
4. 页面结构变更（人为改 hash）后不出现重复角标；
5. 手机端（Torn PDA）可正常加载，不报跨域错误；
6. 成功率上报在值未变化时不产生请求。

## 14. 明确不做

自动点击、自动刷新、定时轮询、页面数据本地计算推荐、Key 上传以外的任何数据上报（如采纳率）、多语言。
