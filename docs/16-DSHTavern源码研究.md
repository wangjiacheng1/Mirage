# 16 · DSH Tavern 源码研究（证据等级 E1-源码）

> 研究对象：https://github.com/flizzywine/dsh-tavern
> 本地路径：`refs/dsh-tavern/`
> 日期：2026-10-19
>
> 🔴 本文所有结论均来自**源码直读**，是目前整个项目证据等级最高的一份文档。
> 前九轮我有 5 次推断失误，全部源于「只能看截图靠猜」。本文用来一次性结清。

---

## 0. 它到底是什么（先把产品定性搞对）

| 项 | 事实 | 来源 |
|---|---|---|
| 包名 | `dsh-profile-tavern` v3.0.0 | `package.json` |
| 本质 | **DSH（DeepSeek 本地宿主）的一个 profile/插件包**，不是独立 App | `package.json` → `dsh.profile.bundles` 含 `@deepseek-ai/dsh-base` |
| 框架 | Cordis（插件化应用框架） | `cordis.patch.yml`、`tavern-plugin/lib/cordis-inspect.js` |
| 移动端 | 有 `android/` 目录 + `dsh-web-mobile` bundle | 顶层目录 |
| 规模 | `tavern-plugin/` 下 796 个 js/ts 文件；测试 **3300 项**，全量跑 197 秒 | `docs/verification/background-failure-recovery-20260929.md` |
| MVU 来源 | **内嵌了 magvarupdate 的完整上游 TS 源码** | `tavern-plugin/lib/vendor/magvarupdate/upstream/src/` |

> 最后一行是本次最大的收获：`vendor/magvarupdate/upstream/src/` 是 **SillyTavern 生态 MVU 框架的原始 TypeScript 源码**。
> 也就是说，我们现在手上有了**行业标准 MVU 的权威定义**，不再需要从角色卡内容反推。

---

## 1. 🔴 更正：我对 `$meta.strictSet` 的理解是错的

### 我之前写的（docs/06 §9，已作废）

> `strictSet` 语义 ≈ 我们的 G1 路径白名单。

### 源码里的真相

`upstream/src/function/update_variables.ts`：

```ts
const strict_set = schema?.strictSet ?? false;   // 默认 false

case 'set': {
    // ① 路径必须已存在 —— 这是无条件行为，与 strictSet 无关
    if (path !== '' && !_.has(variables.stat_data, path)) {
        outError(tr('runtime.variableUpdate.setPathMissing', { path, reason }));
        continue;
    }
    ...
    // ② strictSet 真正控制的是这个
    if (!strict_set && Array.isArray(oldValue) && oldValue.length === 2
        && typeof oldValue[1] === 'string' && !Array.isArray(oldValue[0])) {
        // 旧值是 ValueWithDescription<T> 即 [值, "描述"] 时，
        // 只替换第 0 个元素，保留描述文字
    }
}
```

**结论（两条，都很重要）：**

| # | 结论 | 对我们的影响 |
|---|---|---|
| ① | **路径白名单是 MVU 的无条件默认行为**，不是开关 | 我们的 **G1 可以放心默认开**，而且这是对齐行业标准、不是额外限制。比我原以为的「要靠卡作者开 strictSet」底气足得多 |
| ② | `strictSet` 真实语义是：**关闭对 `[值, "描述"]` 二元组的特殊处理**。`false`（默认）时 `_.set(path, 新值)` 只改值、保留描述；`true` 时整体替换 | 我们必须支持 **ValueWithDescription** 这个数据形态，否则导入带描述的卡会把描述文字冲掉。记为 `B-15` |

> 这是我第 6 次推断失误。根因同前五次：**从角色卡内容反推框架语义**。
> `DiLaoJSON.json` 里写了 `{"$meta":{"strictSet":true}}`，我看到卡作者开了它、
> 又看到该卡行为严格，就把两件事关联成因果。实际上它控制的是完全另一回事。
> 教训已追加进 AGENTS.md 铁律一。

---

## 2. MVU 命令集（我们做 ST 互通的接口定义）

`upstream/src/variable_def.ts` + `update_variables.ts`：

| 命令 | 参数 | 别名 | 语义 |
|---|---|---|---|
| `set` | `(path, newValue)` 或 `(path, expectedOldValue, newValue)` | — | 绝对赋值。**三参数形式带乐观锁**：旧值对不上就不改 |
| `add` | `(path, delta)` | — | 数值增减 / 布尔取反 |
| `insert` | `(path, value)` 或 `(path, indexOrKey, value)` | `assign` | 插入 |
| `delete` | `(path)` 或 `(path, indexOrKeyOrValue)` | `remove`、`unset` | 删除 |
| `move` | `(from, to)` | — | 移动 |

**每条命令都带 `reason` 字段** —— 这就关掉了我之前的待办「是否有自然语言变化溯源」：
**有**。渲染时拼成 `(原因)` 附在变更后面。

还有一个值 `reason === 'json_patch'` 用于标记「这条命令是从 JSON Patch 转过来的」，
说明 MVU 也支持 **JSON Patch 作为输入格式**。

### 对我们的决策影响

| 项 | 决定 |
|---|---|
| 核心层命令集 | 只做 `Set` / `Delta` / `Insert` / `Delete`（`move` 使用率低，M2 再说） |
| 别名归一 | 放在 adapter 层：`remove`/`unset`→`Delete`，`assign`→`Insert`。核心层不认别名 |
| `set` 的三参数乐观锁 | 🆕 **值得抄**。它能挡住「模型基于过期状态做结算」。记为 `B-16` |
| 我们的主力操作 | 仍然是 `Set`（绝对新值），这与 M0 的 `E-3b` 结论一致 |

---

## 3. 结算状态机：是 **7 态**，不是我以为的 5 态

`tavern-plugin/lib/client.js` → `TavernMvuReceipt`（约 13120~13175 行）：

```js
const status = ["pending","updated","partial","error","stale","interrupted","unchanged"]
    .includes(receipt.status) ? receipt.status : "unchanged";

const labels = {
  pending:     props.busy ? "变量结算中…" : "变量结算等待中",
  interrupted: "变量结算已中断",
  updated:     "变量已更新 · N 项" + sideEffectSuffix,
  partial:     "变量部分更新 · N 项成功 · M 项失败",
  error:       "变量更新失败 · M 项",
  stale:       "变量结算已过期，未覆盖当前状态",
  unchanged:   sideEffects.length > 0 ? "Agent 未更新变量" : "本轮变量未更新",
};
```

我只在截图里看到 4 态（进行中/成功/无变化/失败），漏了 `partial`、`stale`、`interrupted`。

### 回执数据模型（已直接抄进 `SettlementReceipt.kt`）

```
receipt = { turn, status, summary, changes[], sideEffects[], failures[] }
change  = { operation, path, before, after }
operationLabels = { set:"设置", replace:"设置", add:"新增", delta:"增减",
                    insert:"新增", delete:"删除", remove:"删除", move:"移动" }
```

`sideEffects` 会**过滤掉** `/delta_data`、`/display_data`、`/schema` 三个内部路径：

```js
return !/^\/(?:delta_data|display_data|schema)(?:\/|$)/.test(String(change.path || ""));
```

> 这是个很细但很体面的设计：框架自己的簿记字段不该出现在玩家的回执里。
> 我们的回执也要做同样的过滤。

### 重试按钮的文案逻辑（抄）

```js
props.busy          ? "结算中…"        // 禁用
: retrying          ? "重试中…"
: status==="pending"? "重新投递结算"
: ["error","stale","interrupted","partial"].includes(status) ? "重试变量结算"
: /* updated | unchanged */                                    "重新结算变量"
```

---

## 4. 🆕 重大发现：重算时可以带「指导意见」

```js
await askTavernText({
  title: "重新结算变量",
  description: "指导意见（选填），仅对本次结算生效。正文保持不变。",
  placeholder: "例如：这轮还没有交付物品，不要扣除库存。",
  allowEmpty: true, maxLength: 4000, confirmLabel: "重新结算",
  onSubmit: guidance => rpc("retrySettlement", { turn, guidance }, sessionId)
});
```

**用户可以用一句自然语言纠正结算结果，而正文完全不变。**

这解决了一个我们迟早会遇到的死结：
玩家觉得数值算错了，但不想重写剧情 —— 重roll 正文就会毁掉他喜欢的那段文字。

> 📌 **产品建议（PM 视角）：这个功能必须进 M2，优先级高。**
> 它和我们「确定性结算」的支柱 ② 是天然一对：守卫负责挡住机器的错，
> 指导意见负责修正**守卫挡不住的语义错**（比如「这轮没交付物品」这种只有玩家知道的上下文）。
> 记为 `B-17`。

另一条相关的源码线索（`tavern-plugin/lib/index.js:4123`）：

```js
throw new Error('这轮旧记录没有结算前快照，无法安全重新结算变量')
```

→ **重算依赖「结算前快照」**。证实了我们「状态存事件流 + 每轮存 pre-settlement snapshot」
的设计方向是对的（铁律三）。`StateGuards.apply()` 要求传入 `current` 快照而非可变状态，
正是为此。

---

## 5. 并发与失败处理（关掉待办「异步更新 bug」）

### 契约（`CONTEXT.md`）

| 规则 | 原文要点 |
|---|---|
| 正文优先 | 「Foreground Turn 成功后立即推进 revision 并建立 checkpoint；**后续状态结算失败不能撤销正文，也不能阻止下一轮**」 |
| 串行 | Background Agent **串行**执行世界书筛选、状态结算、候选生成 |
| 防覆盖 | 「旧 operation 的迟到结果**不能覆盖**更新 revision 的状态」 → 这就是 `stale` |
| 超时 | 「后台模型阶段**连续 5 分钟没有有效流式输出**时中断并记录失败；**运行时长本身不构成失败**」 |
| 取消 | 超时或主动停止后**不复用**可能未响应取消的 Agent；迟到工具调用被拒绝；重试使用新后台 Session |
| 事务 | 校验成功的 effect 先落盘成 delivery 保存点，再通过 Story Timeline **原子提交** |
| 断线 | 「连接恢复后使用已保存提交接续，**不重新调用模型**」 |

### 🔴 用户反馈的「MVU 经常更新失败」—— 在源码里找到了根因

`docs/verification/background-failure-recovery-20260929.md` 原文：

> 失败轮编辑正文后，Timeline revision 增加，但原 body operation 的 **committedRevision 未更新**。
> 重试生成的 MVU effect 因旧 Round 版本被判 **stale**，body.background 仍停在 running，
> 下一次调度报 **BACKGROUND_BUSY**。

**这就是用户看到的「更新失败」**，而且是 DSH 官方自己记录的真实 bug，修复提交 `2b36a65e`。

还有第二个并发 bug（同文档）：

> 快速连续发送存在前台模板并发冲突：`Tavern Chat 已被另一项操作修改，拒绝覆盖冲突字段：promptTemplateInput`

根因是 finalize 时「修改与删除同一字段」被三方合并判为冲突；
修复方式是把正文提交放进 `updateChat` 的**原子更新**，在锁内读取最新输入。

### 对我们的影响

| 决策 | 内容 |
|---|---|
| 超时策略 | ✅ 抄：**按「流式输出空闲时长」超时，不按总时长**。推理模型思考两分钟是正常的，按总时长会误杀 |
| 版本绑定 | ✅ 抄：每个结算任务绑定 `(branchId, revision)`，回来时校验，不匹配直接置 `Stale` 丢弃 |
| 自动重试 | ⚠️ **分层看，见 §6（已于 2026-10-19 更正）**：宿主层不自动重试，但 **MVU 框架层默认带重试策略，甚至有并发扇出** |
| 并发 | ✅ 抄串行 + 单飞。但要**从一开始就用单一状态机管理**，不要像 DSH 那样靠多个字段（`committedRevision` / `body.background`）手工同步 —— 它的 bug 正是出在这两个字段没同步 |

---

## 6. 待办 ①：结算失败是否自动重试（⚠️ 2026-10-19 已更正，原结论只对一半）

证据三处互相印证：

1. `CONTEXT.md`：「结算失败会**暴露重试入口**但不阻塞下一次 Foreground Turn」
2. `CONTEXT.md`：「主动停止、目标过期、初始化或校验失败**不自动接续**」
3. 验收文档里全部是手动点击「重试变量结算」的 E2E 步骤

唯一的自动行为是**断线恢复**：「仅对待办会话自动复查，连接恢复后使用**已保存的提交**接续，**不重新调用模型**」。

> 注意这个区分很精确：**恢复 ≠ 重试**。
> 恢复是「模型已经返回了，只是没写进去，现在补写」；重试是「重新花钱调模型」。
> 前者可以自动，后者必须用户点。
>
> 🔴 **2026-10-19 更正：上面这段结论的适用范围被我写错了，而且我据此写了「产品卖点」。**
>
> 本节的三处证据全部来自 **DSH Tavern 宿主层**（`CONTEXT.md` + 验收文档，讲的是
> `body.background` / `Round` / `Session` 这一套）。但**结算真正发请求的那一层不是宿主，
> 是内嵌的 MVU 上游框架 `magvarupdate`** —— 读它的源码后发现：
> **它默认就带自动重试，而且提供「同时请求多次」的并发扇出模式。**
>
> 详见 **§12（E1-源码，证据等级高于本节的 E2/文档级证据）**。
>
> 教训：**子系统的结论不等于整个产品的结论。**「DSH Tavern 不自动重试」这句话里的
> 「DSH Tavern」到底指宿主还是指它内嵌的 MVU 框架，我没有区分就下了结论，
> 还直接升级成了对外卖点。凡要写「竞品不做 X」，必须先说清**是哪一层不做**。

---

## 7. 待办 ⑥：上下文压缩策略（全文抄录要点）

`docs/auto-compaction.md`：

| 项 | DSH 的做法 |
|---|---|
| 默认 | **手动压缩**。不自动压 |
| 自动模式 A | 每 N 轮（1–1000）。只统计**已完成的不同剧情轮**；重写同一轮、工具调用、候选生成、生图、后台任务**不计数** |
| 自动模式 B | 上下文达 X%（10–95）。按**前台当前模型窗口**估算；模型容量未知时提示暂停判断 |
| 两模式 | 互斥 |
| 触发时机 | 先等本轮后台结算结束，再**联合压缩前台 + 绑定的后台**，然后继续下一轮 |
| 部分失败 | 只补失败的一侧 |
| 🔴 **分卷摘要** | **每次压缩只把新压掉的轮次写成一卷**（如「第 31–50 轮剧情摘要」），**之前的卷原样保留、不再重新总结** |
| 二次合并 | 所有卷加起来超过模型窗口 **15%** 时，才合并一次成「前情总览」 |
| 检索兜底 | 摘要开头提示模型：需要原话或细节时，用**历史检索工具**按关键词或轮次查原文 |
| 诚实声明 | 「压缩可能遗漏细节，不等于无损记忆或备份，重要故事仍建议导出」 |

### 🔴 分卷摘要是本节最重要的发现

**反面教材就是用户实测的 DZMM**：轮数一长就「遗忘历史和设定细节（服装、口吻）」。
根因大概率是**反复总结同一段历史** —— 每次压缩都把「旧摘要 + 新内容」再总结一遍，
早期剧情经过 N 次有损压缩后彻底糊掉。

分卷方案从机制上杜绝了这件事：**每一卷只被总结一次**。

> 📌 这条直接写进 M3 的上下文模块设计。记为 `B-18`。
> 「15% 阈值才二次合并」这个数字也先照抄，等我们有自己的实测数据再调。

---

## 8. 其它值得记的发现

### 8.1 结算用 tool calling，而且提示词极短

`tavern-plugin/prompts/posture-settlement.md` **全文**：

> 你是剧情姿势结算器。以上一轮结算姿势为起点，根据最新一轮对话更新状态。必须调用 posture_submit 提交结果。用第三人称写主要人物结束时的位置、姿势、动作和必要衣着；只写当前状态，不写历史，不解释，不输出 JSON。

**一共 3 句话。**

这是对我们 M0 的 `D5` 对照组结论（删掉严格约束后断言反而从 85% 升到 97%）的**强力外部佐证**：
行业里做得最好的实现，结算提示词也是极简的。约束靠 tool schema 和代码，不靠散文。

> ✅ **ADR-010 / ADR-012 得到独立验证**。我们的方向是对的。

### 8.2 ST 预设是「只读导入源」，不整份运行

`CONTEXT.md` → `External Preset`：

> 从 SillyTavern 等外部系统导入的**只读来源**……用户在预设库中查看它，并**手动选择**需要启用的提示词和正则；**系统不会直接运行整份预设**。

然后生成 `Preset Selection Snapshot`（自包含运行快照）→ `Internal Preset Projection`（确定性生成的内部请求结构）。

> 📌 **这正面回答了用户本轮问的那个问题**（见 §9）。

### 8.3 「兼容模式」是个独立编排策略

`Compatibility Mode`：用户可在设置里主动开启的实验性模式，**不运行后台状态结算**，
投影为 SillyTavern 的消息顺序。默认关闭，已有对话不受设置变化影响。

> 📌 这是「既要 ST 兼容、又要自己的引擎」的标准解法：**做成两条编排策略，不做成一堆 if**。
> 我们应该照抄这个架构（见 §9）。

---

## 9. 回答用户的问题：ST 卡迁移过来，代码层不适配的部分怎么办？

> 用户原话：「我们一开始定了要能够让 ST 卡适配迁移，那如果 ST 角色卡迁移过来我们的代码层不适配的部分我们怎么做兼容呢？」

**这个担心完全成立，而且 DSH 已经给出了经过 3300 项测试验证的答案。**

### 核心思路：**卡是只读来源，不是运行时**

```
ST 角色卡（只读，原样保存，绝不改写）
        │
        ▼  导入预检（一次性，用户可见、可确认）
能力清单  ← 「这张卡用了什么？我们支持哪些？」
        │
        ▼
运行快照 Mirage Snapshot（自包含、确定性）
        │
        ▼
引擎执行（G1~G8 守卫在这一层）
```

### 四级降级策略

| 级别 | 情况 | 处置 | 用户看到什么 |
|---|---|---|---|
| **L0 原生** | 卡只用了我们支持的能力 | 直接跑 | 无提示 |
| **L1 降级可用** | 用了我们部分支持的能力，如正则里有简单替换 | 跑支持的部分 | 导入预检列出「X 项已启用，Y 项已跳过」 |
| **L2 兼容模式** | 卡严重依赖我们不做的能力（内嵌 JS 面板、ST 专有宏） | **关闭我们的结算引擎，退化成纯对话 + 原样透传** | 「这张卡使用了 Mirage 不执行的脚本，已切换到兼容模式：叙事正常，但状态面板和数值守卫不可用」 |
| **L3 拒绝** | 文件结构损坏 / 版本无法识别 | 不导入 | 明确报错 + 指出哪一段解析不了 |

**关键在 L2。** 它来自 DSH 的 `Compatibility Mode`（§8.3）：
宁可**整体退化成一个诚实的弱模式**，也不要在引擎里塞满 `if (卡是旧的)` 的补丁。
后者会让守卫逻辑在半年内烂掉。

### 守住兼容硬底线的具体机制

| 机制 | 说明 |
|---|---|
| **默认只开 Tier A** | G1（路径）+ G2（类型）+ G8（空 delta）。这三条是 MVU **本身就有的**行为（§1 结论①），所以不可能比原生酒馆更差 |
| **Tier B/C 默认关** | 值域、枚举、幅度、单调全部默认不启用，由用户在导入预检里逐项打开 |
| **任何守卫都不删数据** | 被拒绝 = 保持原值，不是清零 |
| **原始卡原样保留** | 我们的元数据只写 `$meta.mirage` / `extensions.mirage`，导出时往返无损 |
| **一键关闭** | 对话设置里有「暂停数值守卫」开关。用户觉得我们管太多，一键退回 ST 行为 |

### 还要做一件 DSH 没做的事：**兼容性自测**

用 `refs/cards/` 里收集的真卡跑一个 `ImportCompatibilityTest`：
每张卡跑一遍导入 → 断言「不崩溃 + 降级级别符合预期 + 导出后与原文件语义等价」。
这是我们宣称「ST 卡互通」的唯一硬证据，不能靠嘴说。记为 `B-19`。

---

## 10. 本次关闭的待办

| # | 原问题 | 结论 | 证据 |
|---|---|---|---|
| ① | 结算失败是否自动重试 | **不自动**；只有断线恢复会接续已保存结果，且不重新调模型 | `CONTEXT.md` ×2 + 验收文档 |
| ② | 是否有自然语言变化溯源 | **有**。每条 Command 带 `reason`，回执有 `summary` | `variable_def.ts` / `client.js` |
| ③ | 触发时机与并发控制 | 串行 + revision 绑定 + stale 丢弃 + 流式空闲 5 分钟超时；用户遇到的 bug 是官方已记录的 `committedRevision` 未更新 | `CONTEXT.md` + `background-failure-recovery-20260929.md` |
| ④ | `$meta.strictSet` 语义 | 🔴 **我原先理解错了**。它控制 ValueWithDescription 的处理，不是路径白名单。路径白名单是 MVU 无条件默认行为 | `update_variables.ts` / `schema.ts` |
| ⑤ | 状态机 | **7 态**（我只看到 4 态）：pending/updated/partial/error/stale/interrupted/unchanged | `client.js` |
| ⑥ | 上下文裁剪策略 | 分卷摘要 + 15% 二次合并 + 历史检索工具兜底；默认手动，两种自动模式互斥 | `docs/auto-compaction.md` |

## 11. 新开的待办

| # | 内容 | 优先级 |
|---|---|---|
| `B-15` | 支持 ValueWithDescription（`[值, "描述"]`）数据形态 | M1 必须 |
| `B-16` | `set` 三参数乐观锁（带 expectedOldValue） | M2 |
| `B-17` | 重算时可填「指导意见」，正文不变 | **M2 高优先级** |
| `B-18` | 分卷摘要压缩策略 | M3 |
| `B-19` | `ImportCompatibilityTest` 真卡兼容性自测 | M2 |
| `B-20` | 结算任务状态机用**单一状态字段**管理，不学 DSH 的多字段手工同步 | M2 |


---

## 12. 结算的触发时机与重试策略（E1-源码，2026-10-19 第 12 轮）

> 证据等级 **E1-源码**，直接读的是 MVU 上游框架 `magvarupdate` 的 TypeScript 源码：
> `refs/dsh-tavern/tavern-plugin/lib/vendor/magvarupdate/upstream/src/function/update/`
> 本节结论**高于 §5/§6 的文档级证据**，冲突处以本节为准。

### 12.1 触发时机：正文**完整收到之后**，不是边流边算 ✅ 坐实

`index.ts` 把结算挂在 SillyTavern 的 `MESSAGE_RECEIVED` 事件上：

```ts
controlledStoppableEventOn(tavern_events.MESSAGE_SENT,     handleVariablesInMessage)
controlledStoppableEventOn(tavern_events.MESSAGE_RECEIVED, _.throttle(onMessageReceived, 3000))
```

| 发现 | 对我们的影响 |
|---|---|
| 结算在 `MESSAGE_RECEIVED`（正文收完）后触发 | ✅ **正文流式通道与结算通道天然解耦、天然串行** → 可以安全地先做正文流式、后做结算，不会返工 |
| `_.throttle(..., 3000)` 3 秒节流 | ✅ 抄。事件可能重复触发 |
| `if (message_content.length < 5) return` | ✅ **抄这个坑**。源码注释原文：「MESSAGE_RECEIVED 有时候也会在请求的一开始递交，会包含一个 `...` 的消息」 |
| `if (SillyTavern.chat.length <= 1) return` | ✅ 抄：**第一楼（开场白）不结算** |
| 结算成功后把结果**追加写回正文消息末尾**（`message.trimEnd() + '\n\n' + result`） | ⚠️ 我们**不抄**：我们把状态存成独立事件流（铁律 3），不污染正文 |

### 12.2 两条路线并存，且方案 A 是永久兜底

`on_message_received.ts` 的分支逻辑：

```
更新方式 === '随AI输出'                    → handleVariablesInMessage()   // 方案 A：正文内标签
应答格式 === '工具调用' && !支持函数调用     → handleVariablesInMessage()   // 降级
!await isExtraModelSupported()            → handleVariablesInMessage()   // 降级
否则                                       → invokeExtraModelWithStrategy() // 方案 C：独立结算
```

而且**方案 C 失败后仍然会再走一遍方案 A**：

```ts
} else {
    toastr.error(tr('runtime.extraModel.updateFailed'), ...);
}
await handleVariablesInMessage(message_id);   // ← 无论成败都执行
```

> 📌 **这验证了 `ADR-010`「生成走 C、解析认 A」是对的，但也补上了我们漏掉的一环：
> 方案 A 不只是「也要支持」，它是 C 不可用或 C 失败时的兜底路径，必须实装。**
> 我们原先的理解偏向「C 为主、A 为辅」，实际竞品是「**C 为主、A 为网**」。

### 12.3 🔴 重试策略：三种模式，其中两种会成倍花钱

`invoke_extra_model.ts` 的 `invokeExtraModelWithStrategy()`，配置项 `请求方式` 三选一：

| 模式 | 行为 | 成本倍数 |
|---|---|---|
| `依次请求，失败后重试` | 串行，最多试 `请求次数` 次，一旦成功立即返回 | 1 ～ N |
| `同时请求多次` | `Promise.any` 同时发 N 个请求，取第一个有效结果，然后 `stopGenerationById` 取消其余 | **≈ N（见下）** |
| `先请求一次, 失败后再同时请求多次` | 先单发一次，失败再扇出 N-1 个 | 1 或 ≈ N |

并发模式的核心代码：

```ts
const uuids = _.times(times, uuidv4);
// 在函数调用的模式下，允许接受 **任意** 有效的函数结果，因此被允许被覆盖。
return await Promise.any(uuids.map(recordedInvoke));
// finally:
uuids.forEach(stopGenerationById);
```

### 12.4 ⭐ 由此得到一个 E1×E1 互证的差异化落点

把两条**都是 E1 级**的证据摆在一起：

| 证据 | 来源 |
|---|---|
| 竞品的并发结算模式会同时发 N 个请求，靠事后 `stopGenerationById` 取消多余的 | **E1-源码**（本节） |
| **中断不省钱**：客户端断开 ≠ 上游停止。实测 3.5s 断开，后台仍跑满 20s、生成 315 token、**全额计费**，甚至比跑完的还多 5% | **E1-实测**（`docs/14 §13.2`） |

→ **结论：竞品那个「取消其余请求」的动作在计费上几乎无效，「同时请求多次」就是实打实的 N 倍结算成本，而用户对此毫无感知。**

这不是「竞品做得差」，它是为了提高结算成功率做的合理工程选择（结算失败率确实高，见用户实测）。
但它**暴露了一个真实的、可验证的空档**，正落在支柱 ③「成本透明」上：

| 我们要做的 | 为什么能做 |
|---|---|
| 结算重试策略在设置里**标注成本倍数**（「同时请求 3 次 ≈ 3 倍结算费用」） | 我们知道中断不省钱，竞品的 UI 没有这个提示 |
| 重试/扇出产生的费用**进本轮成本回执**，不隐藏 | `J-7`~`J-11` 已规划的成本面板天然能承载 |
| **默认用串行重试**，并发扇出做成需显式开启的高级项 | 默认值保护用户钱包 |

> ⚠️ 注意措辞纪律：**不得**写成「竞品偷偷花你的钱」。准确表述是
> 「竞品为提高成功率提供并发结算，但未提示成本倍数；我们提供同样的能力并把成本摊开」。

### 12.5 其它可直接抄的实现细节（E1-源码）

| 细节 | 源码 | 抄不抄 |
|---|---|---|
| 模块级 `is_analysis_in_progress` 标志，重入直接 `return null` | `invokeExtraModelWithStrategy()` 开头 | ✅ 串行锁。**但按 §5 的教训用单一状态机管，不要散落多个布尔** |
| 手动点停止（`e === 'Clicked stop button'`）→ **不再重试** | `safeInvoke()` + 两处 `if (is_manual_canceled) return null` | ✅ 必抄。用户叫停就是叫停，不能继续花钱 |
| `should_stream: 兼容假流式` —— 结算请求的流式是**可选配置项** | `requestReply()` | ✅ 印证：**结算通道不需要真流式**，M2 可以先用非流式实现，省一半工作量 |
| 结算有**独立**的 `api地址 / 密钥 / 模型名称 / max_tokens / temperature / top_p / top_k` | `config.custom_api` | ✅ 印证 M0 的 D3（结算换便宜模型省 2/3 成本）：**结算必须是独立 Provider 配置，不是复用叙事的** |
| `max_chat_history` —— 限制喂给结算模型多少历史 | `GenerateRawConfig` | ✅ 成本旋钮，抄 |
| 临时切 `response_format: json_object` 再恢复（`setTemporaryJsonObjectResponseFormat` / `restore...`） | 同文件 | 🟡 我们直接在请求里带，不需要这种全局改了再还原的体操 |
