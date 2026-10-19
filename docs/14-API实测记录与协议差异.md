# 14 · API 实测记录与协议差异(第一次实测)

| 项 | 值 |
|---|---|
| 文档版本 | **v1.0** |
| 实测人 | 用户(2026-10-16) |
| 原始材料 | `大模型请求测试记录.pdf`(35 页) |
| 覆盖服务商 | **DeepSeek**、**OpenRouter**(经由 OpenRouter 调 `z-ai/glm-5.1`、`qwen/qwen-2.5-72b-instruct`) |
| 证据等级 | 本文 **§2~§4 全部为 E1**(真实请求与真实响应体);§5 的竞品做法标注为 E2 |

> **这是项目第一份 E1 级技术证据。** 在此之前 `docs/02` 的 A 模块兼容层设计全部是 E2/E3 级推导。
> 本次实测**推翻/修正了 7 处既有文档内容**,见 §6。

---

## 0 · 🔴 安全事件(已发生,待你处置)

| 项 | 内容 |
|---|---|
| 事件 | 测试记录 PDF 中包含**两个完整明文 API Key** |
| 违反 | `AGENTS.md` 铁律 4、`docs/07` §1、`docs/12` §2.4 的三条安全红线 |
| 暴露范围 | 文件已传给我(模型),即已离开你的本机 |

| 平台 | Key 前缀 | PDF 中明文出现次数 |
|---|---|---|
| DeepSeek | `sk-cb3826545…` | 2 次(首页做了掩码,但正文 curl 是完整明文) |
| OpenRouter | `sk-or-v1-1d89c2acfb…` | 9 次 |

**必须做的三件事:**

1. 到两个平台后台 **吊销(revoke)旧 Key → 新建 Key**
2. 查 OpenRouter 的 **Activity / Usage 页**,确认没有你不认识的消费
3. 这份 PDF **不要外发、不要进 git**;以后记录测试请用 `§7.3` 的脱敏流程

**为什么这件事值得单独立一节:** 这正是 `docs/07` 预判的"最常见泄露点"的真实发生。
泄露路径不是代码、不是日志,而是**人工整理的文档**——这是原文档没覆盖的通道,已补进 §7.3。

---

## 1 · 结论速览

### 1.1 做成了什么

| # | 事项 | 结果 |
|---|---|---|
| 1 | DeepSeek 非流式 | ✅ 通 |
| 2 | DeepSeek 流式(SSE) | ✅ 通 |
| 3 | OpenRouter 非流式 | ✅ 通 |
| 4 | OpenRouter 流式(SSE) | ✅ 通 |
| 5 | 多轮对话(system + user + assistant + user) | ✅ 通,3 轮递进 |
| 6 | 带预设(system prompt)的多轮 | ✅ 通 |
| 7 | 叙事类任务(轻小说/悲剧结尾) | ⚠️ 通但**全部被截断**,见 §4 |

**对照 `docs/12 §4.4` Step 1 / `§4.5` Step 2 的验收口径:**

| 验收项 | 状态 |
|---|---|
| Step 1 · 返回 JSON 结构 | ✅ 已记录 |
| Step 1 · `usage` 格式 | ✅ 已记录,且发现两家差异巨大(§3) |
| Step 1 · **故意填错 Key 看 401 响应体** | ❌ **未做** |
| Step 2 · ① `data:` 行前缀格式 | ✅ 已记录 |
| Step 2 · ② **结束标志(`[DONE]`?)** | ❌ **未记录** —— PDF 全文无 `[DONE]` |
| Step 2 · ③ keep-alive / 注释行 | ❌ 未记录 |
| Step 2 · ④ 首 chunk 延迟 | ❌ 未记录 |
| Step 2 · ⑤ 流式下 `usage` 在哪个 chunk | ⚠️ 部分(见 §3.4) |
| Step 2 · ⑥ Ctrl+C 打断的服务端反应 | ❌ 未做 |

**结论:Step 1 完成约 70%,Step 2 完成约 35%。** 缺口清单见 §7。

### 1.2 两个必须马上记住的结论

> **结论 A(头号):`max_tokens` 是 "思考 + 正文" 共用的总预算。**
> 实测 6 次响应里 **4 次 `finish_reason` 是 `length`**,其中 1 次 `content` 直接为 `null`——
> 1024 的预算被 reasoning 吃光,一个字正文都没输出,**但照样全额计费**。
> 对一个角色扮演 App,这是**会直接毁掉产品体验的一级问题**。

> **结论 B:两家的"思考过程"字段名不一样,且不止一处。**
> DeepSeek 叫 `reasoning_content`,OpenRouter 叫 `reasoning` **+** `reasoning_details[]`。
> 这证实了 `R-T1`(协议差异)是真风险,A 模块的**响应归一化层从"建议"升级为"必做"**。

---

## 2 · 实测事实:DeepSeek(E1)

### 2.1 端点与请求

```
POST https://api.deepseek.com/chat/completions
Authorization: Bearer sk-...
Content-Type: application/json
```

> ⚠️ **注意:路径里没有 `/v1`。** 这与 `docs/12 §2.3` 示例写的 `https://api.deepseek.com/v1` 不一致。
> 官方文档同时标注 `base_url` 可写 `https://api.deepseek.com` 或 `https://api.deepseek.com/v1`(为兼容 OpenAI SDK)。
> → **我们的 App 不能假设用户填的 base_url 一定带 `/v1`**,拼接逻辑必须容错。详见 §6 修订 1。

实测请求体:

```json
{
  "model": "deepseek-flash",
  "messages": [
    {"role": "system", "content": "You are a helpful assistant."},
    {"role": "user", "content": "你好!你现在使用的是什么模型？你能做些什么"}
  ],
  "thinking": {"type": "enabled"},
  "reasoning_effort": "high",
  "stream": false
}
```

| 字段 | 说明 |
|---|---|
| `model: "deepseek-flash"` | 模型 ID,非产品名 |
| `thinking: {"type": "enabled"}` | **DeepSeek 私有字段**(OpenAI 规范里没有),开关思维链 |
| `reasoning_effort: "high"` | 思考强度。注意这个字段名 OpenAI 也有,但语义/取值未必一致 |

### 2.2 非流式响应(关键字段)

```json
{
  "id": "4f0c1334-...",
  "object": "chat.completion",
  "created": 1792121460,
  "model": "deepseek-flash",
  "choices": [{
    "index": 0,
    "message": {
      "role": "assistant",
      "content": "你好！我是你当前对话中的 AI 助手。…",
      "reasoning_content": "We need answer in Chinese. User asks: …"
    },
    "logprobs": null,
    "finish_reason": "stop"
  }],
  "usage": {
    "prompt_tokens": 46,
    "completion_tokens": 795,
    "total_tokens": 841,
    "prompt_tokens_details": {"cached_tokens": 0},
    "completion_tokens_details": {"reasoning_tokens": 540},
    "prompt_cache_hit_tokens": 0,
    "prompt_cache_miss_tokens": 46
  },
  "system_fingerprint": "aeb56401ca74e127821c4f9126dcb669"
}
```

**三个要点:**

1. **`reasoning_content` 与 `content` 平级**,都在 `message` 下。
2. `completion_tokens: 795` 中 **`reasoning_tokens: 540` 占 68%**。即这次请求 2/3 的输出费用花在了用户看不到的思考上。
3. **`prompt_cache_hit_tokens` / `prompt_cache_miss_tokens` 是 DeepSeek 独有的**,说明它做了 **Prompt 前缀缓存**。这对我们有直接的架构含义,见 §6 修订 4。

### 2.3 流式响应

逐 chunk 形如:

```
data: {"id":"52e1b252-...","object":"chat.completion.chunk","created":1792128405,"model":"deepseek-flash","system_fingerprint":"...","choices":[{"index":0,"delta":{"role":"assistant","content":null,"reasoning_content":""},"logprobs":null,"finish_reason":null}]}

data: {... "delta":{"content":null,"reasoning_content":"我们需要"} ...}
data: {... "delta":{"content":null,"reasoning_content":"回答"} ...}
data: {... "delta":{"content":null,"reasoning_content":"用户"} ...}
```

**实测到的行为:**

| 观察 | 事实 |
|---|---|
| 行前缀 | `data: ` + 紧凑 JSON,**每条之间空一行** |
| 首个 chunk | `delta` 里带 `role: "assistant"`,`content: null`,`reasoning_content: ""` |
| 思考阶段 | **`content` 恒为 `null`**,只有 `reasoning_content` 在增量吐字 |
| 分片粒度 | 极细:`"我们需要"` / `"回答"` / `"用户"` / `"。"`,**单个标点也会独立成片** |
| 正文阶段 | (PDF 未截到完整的正文段,待补) |
| **结束标志** | ❌ **未记录** —— 这是 Android 端解析器的关键缺口 |

> 💡 **分片粒度细到单个标点**,直接印证了 `R-T5`(流式 UI 掉帧):一次 800 token 的回复可能触发 **数百次** UI 更新。
> `docs/02` 的"节流 50ms"从优化项变成**必需项**。

---

## 3 · 实测事实:OpenRouter(E1)

### 3.1 端点与请求

```
POST https://openrouter.ai/api/v1/chat/completions
Authorization: Bearer sk-or-v1-...
Content-Type: application/json
HTTP-Referer: <YOUR_SITE_URL>   ← 可选,用于在 OpenRouter 排行榜署名
X-Title: <YOUR_SITE_NAME>        ← 可选,同上
```

实测请求体:

```json
{
  "models": ["z-ai/glm-5.1", "qwen/qwen-2.5-72b-instruct"],
  "stream": true,
  "stream_options": {"include_usage": true},
  "max_tokens": 1024,
  "temperature": 0.7,
  "messages": [...]
}
```

> ⭐ 你用了 **`models`(复数数组)而不是 `model`**。这是 **OpenRouter 私有的"模型回退链"**:
> 首选模型不可用时自动降级到下一个。
> **这是个好东西,但也是个陷阱**——见 §3.3 的 provider 漂移问题。

### 3.2 非流式响应(关键字段)

```json
{
  "id": "gen-1792129855-...",
  "object": "chat.completion",
  "created": 1792129855,
  "model": "z-ai/glm-5.1",
  "provider": "StreamLake",
  "system_fingerprint": null,
  "service_tier": null,
  "choices": [{
    "index": 0,
    "logprobs": null,
    "finish_reason": "stop",
    "native_finish_reason": "stop",
    "message": {
      "role": "assistant",
      "content": "你好！我是Z.ai开发的GLM…",
      "refusal": null,
      "reasoning": "让我分析一下用户的提问。…",
      "reasoning_details": [
        {"type": "reasoning.text", "text": "让我分析一下…", "format": "unknown", "index": 0}
      ]
    }
  }],
  "usage": {
    "prompt_tokens": 15,
    "completion_tokens": 326,
    "total_tokens": 341,
    "cost": 0.001004226,
    "is_byok": false,
    "prompt_tokens_details": {"cached_tokens":0,"cache_write_tokens":0,"audio_tokens":0,"video_tokens":0},
    "cost_details": {
      "upstream_inference_cost": 0.001004226,
      "upstream_inference_prompt_cost": 0.00001449,
      "upstream_inference_completions_cost": 0.000989736
    },
    "completion_tokens_details": {"reasoning_tokens":222,"image_tokens":0,"audio_tokens":0}
  }
}
```

**OpenRouter 独有的、对我们有用的字段:**

| 字段 | 用途 |
|---|---|
| `provider` | 实际承接这次推理的厂商。**同一个模型 ID 会落到不同 provider** |
| `native_finish_reason` | 上游厂商的原始 finish_reason。与归一化后的 `finish_reason` 可能不同 |
| **`usage.cost`** | ⭐ **直接给出本次请求的美元成本**。这让 `D-2`(token/费用统计)在 OpenRouter 上**几乎零成本实现**,不用我们自己维护价格表 |
| `is_byok` | 是否用的你自带的上游 key |
| `refusal` | 拒答原因(OpenAI 规范新增) |
| `reasoning` + `reasoning_details[]` | 思考过程。`reasoning_details` 是结构化分片,为将来多模态/多段思考预留 |

### 3.3 ⚠️ provider 漂移:实测到 5 个不同的 provider

同样请求 `models: ["z-ai/glm-5.1", ...]`,**6 次请求落到了 5 个不同的上游**:

| provider | 出现次数 | 备注 |
|---|---|---|
| **Chutes** | 40 chunk | 流式测试主要落在这里 |
| StreamLake | 2 | |
| Alibaba | 1 | |
| Nebius | 1 | `system_fingerprint` 为 `vllm-0.22.0-tp8-ep-...`,暴露了它跑的是 vLLM |
| Baidu | 1 | |

**这是一个此前完全没预料到的问题,影响三件事:**

| 影响 | 说明 |
|---|---|
| ① **评测不可复现** | `docs/08` 的评测集要靠"同输入同输出"做回归。provider 一换,温度、量化精度、甚至是否返回 `reasoning` 都可能变。**不固定 provider 的评测数据是没有意义的** |
| ② **协议行为不一致** | Nebius 跑 vLLM、Alibaba 跑自家栈,它们对 `tools` / `stream+tools` / 缓存的支持度**不同**。`R-T14` 的验证必须**逐 provider** 做 |
| ③ **成本波动** | 实测同样 1024 completion tokens,成本从 `$0.0042` 到 `$0.0075`,**差 1.8 倍** |

**对策(E1 推导):** 在我们的 App 里,对 OpenRouter 要支持透传
`provider: {"order": ["..."], "allow_fallbacks": false}`,并在评测脚本里**强制固定**。
→ 已登记为新风险 `R-T16`,并写入 `docs/08`。

### 3.4 流式响应

```
data: {"id":"gen-1792130973-...","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"","role":"assistant","reasoning":"1","reasoning_details":[{"type":"reasoning.text","text":"1","format":"unknown","index":0}]},"finish_reason":null,"native_finish_reason":null}]}
```

| 观察 | DeepSeek | OpenRouter |
|---|---|---|
| 思考阶段的 `content` | **`null`** | **`""`(空字符串)** |
| 思考字段 | `reasoning_content` | `reasoning` + `reasoning_details[]` |
| 末 chunk 带 usage | 未记录 | ✅ 带(请求里加了 `stream_options.include_usage`) |

> ⚠️ **`content` 一个是 `null` 一个是 `""`。** 如果 Android 端用 `content.isNullOrEmpty()` 一把梭,
> 两家都能过;但如果用 `content == null` 判断"是否在思考",**OpenRouter 上会直接失效**。
> 这就是归一化层存在的意义。

---

## 4 · 🔴 头号发现:reasoning 吃光 max_tokens

### 4.1 实测数据

| 测试 | model | max_tokens | completion_tokens | reasoning_tokens | content | finish_reason | cost |
|---|---|---|---|---|---|---|---|
| DS 非流式 | deepseek-flash | 未设 | 795 | 540 | ✅ 完整 | `stop` | — |
| OR 非流式 #1 | glm-5.1 | 未设 | 326 | 222 | ✅ 完整 | `stop` | $0.0010 |
| OR 流式 | glm-5.1 | 1024 | 1025 | 1014 | **空** | **`length`** | $0.0043 |
| **叙事第 1 轮** | glm-5.1 | 1024 | 1024 | **1447** ⚠️ | **`null`** | **`length`** | $0.0046 |
| 叙事第 2 轮 | glm-5.1 | 1024 | 1024 | — | ⚠️ 截断 | **`length`** | $0.0042 |
| 叙事第 3 轮 | glm-5.1 | 1024 | 1024 | 963 | ⚠️ 截断 | **`length`** | $0.0075 |

**三条铁一样的事实:**

1. **6 次里 4 次 `finish_reason = "length"`。** 凡是设了 `max_tokens: 1024` 的,**全军覆没**。
2. **叙事第 1 轮:`content` 为 `null`,1447 个 reasoning token 全部花在"构思"上**——
   模型在思考里完整写出了"《借来的晴天》雨之精灵"的故事大纲,**然后预算用完了,一个字都没输出给用户**。
   **你照样付了 $0.0046。**
3. ⚠️ **`reasoning_tokens: 1447 > completion_tokens: 1024`**。这在数学上说不通——
   说明**至少有一家 provider 的 token 统计是不自洽的**。
   → **不要拿 `reasoning_tokens` 做预算计算。** 已登记 `R-T17`。

### 4.2 为什么这对我们是一级问题

| 场景 | 后果 |
|---|---|
| 角色扮演的回复普遍 300~800 字(≈ 500~1300 token) | 加上 reasoning 轻松破 2500 token |
| 用户在设置里看到 "max_tokens: 1024" 这种眼熟的默认值 | 一设就废,**而且现象是"AI 不说话"**,用户只会认为我们的 App 坏了 |
| 思考阶段对角色扮演**没有价值** | 用户要的是"她抬起头看着你",不是"让我分析一下用户的提问" |
| 但你**全额付费** | 实测 reasoning 占输出 token 的 **68%~99%** |

### 4.3 对策(写进 `docs/02` / `docs/05`)

| # | 对策 | 优先级 |
|---|---|---|
| 1 | **角色扮演默认关闭 reasoning**。DeepSeek: `thinking:{"type":"disabled"}`;OpenRouter: `reasoning:{"enabled": false}` | P0 |
| 2 | `max_tokens` **默认不传**(用平台默认上限);若用户要设,**下限强制 2048**,并在 UI 上标注"含模型思考消耗" | P0 |
| 3 | 实现 **`finish_reason == "length"` 的识别与提示**:不能静默吞掉,要显式告诉用户"回复被长度限制截断" | P0 |
| 4 | 实现 **"继续生成"(Continue)**:把已生成的 content 作为 assistant 消息前缀再请一次。SillyTavern 有这个按钮(E2) | P1 |
| 5 | reasoning 内容**单独折叠展示**,绝不混进叙事正文,也**绝不写进对话历史**发回给模型 | P0 |
| 6 | 费用统计优先用 OpenRouter 的 `usage.cost`,DeepSeek 用自维护价格表 | P2 |

> ⚠️ 关于对策 1 的一个**坑**:OpenRouter 的 `reasoning: {"exclude": true}` 和 `{"enabled": false}` **不是一回事**。
> 前者是"模型照样思考、照样计费,只是不返回给你",后者才是真关闭。
> **这一条我标 E2(来自公开文档记忆,本轮未实测),已列入 §7 待验证清单 `V-8`。**

---

## 5 · 回答你在 PDF 里提的 4 个问题

### 5.1 Q1 ·「只测了 DeepSeek 和 OpenRouter,且我所在地区 OpenRouter 不支持」

**这不是缺陷,反而帮我们把目标用户假设钉死了。**

两家已经覆盖了 `docs/12 §2.1` 要求的三类中的两类(便宜的 + 聚合型),**够用了**。
但你带回的"地区不支持"是个**产品级信息**:

| 推论 | 对文档的影响 |
|---|---|
| 我们的早期用户很可能**和你处境相同**:拿不到 Claude/GPT,主力是 DeepSeek + 国内可直连的服务 | `docs/01` 的目标用户画像要补这一条 |
| 那么"模型能力"的基线要按 **GLM-5.1 / DeepSeek / Qwen 这档**来定,而不是按 Claude 来定 | ⭐ **这直接影响 `R-T14`**:这档模型的 Function Calling 可靠性**明显弱于 Claude**,`E-10`(JSON 代码块方案)成为主方案的概率又上升了 |
| 用户会**大量使用中转/代理服务**(base_url 五花八门) | `A` 模块的 base_url 必须完全自由填写,不能做白名单 |

> ⚠️ 补一句**风控提醒**:OpenRouter 明确声明你所在地区不支持,你仍在使用,**账号存在被封的风险**。
> 请**不要把它当作长期主力**,更不要在上面充大额。评测主力建议用 DeepSeek。

### 5.2 Q2 ·「流式输出需要在 `stream` 字段中开启」

✅ 正确,且两家一致。补三点你没注意到的:

1. **`stream_options: {"include_usage": true}`** —— 你在 OpenRouter 上用了,这才是流式下能拿到 `usage` 的原因。**DeepSeek 那次流式你没加**,所以没拿到 token 统计。这是 OpenAI 规范里的标准字段,但**不是所有服务商都支持**。
2. 流式请求在 HTTP 层要求 **`Accept: text/event-stream`**(curl 不加也能工作,但 Android 的 Ktor 上建议显式带)。
3. ⭐ **你漏了最关键的一项观察:流到底怎么结束的。** OpenAI/OpenRouter 规范是以 `data: [DONE]` 收尾,但你的 PDF 里**一次都没出现**——可能是整理时被截掉了,也可能 DeepSeek 真的不发。**这个必须补测**(§7 `V-9`),因为 Android 端的解析器要靠它判断"正常结束"还是"断流",**判断错了就会把一次成功的回复标成失败**。

### 5.3 Q3 ⭐ ·「content 有时为 null,怎么区分是出错还是模型只输出 reasoning?」

**这是你提的最好的一个问题,也是本次实测最有价值的产出。**

**核心答案:不要用 `content` 判断成败。`content` 为空是"结果",不是"原因"。判定依据是 HTTP 状态码 + `finish_reason` 的组合。**

#### 判定矩阵(直接作为 `C-10` 错误处理矩阵的实现依据)

| HTTP | `finish_reason` | `content` | `reasoning` | 判定 | UI 应该怎么做 |
|---|---|---|---|---|---|
| 非 2xx | — | — | — | **请求失败** | 按错误码走错误处理(401/429/5xx) |
| 2xx | `stop` | 有内容 | 任意 | ✅ **正常完成** | 正常渲染 |
| 2xx | `stop` | **空/null** | **有** | ⚠️ **空回复**(模型思考完决定不说话) | 提示"模型未产生回复",给**重试**按钮 |
| 2xx | `stop` | **空/null** | **也空** | ⚠️ **异常空响应** | 同上,并记入诊断日志 |
| 2xx | **`length`** | **空/null** | **有** | 🔴 **思考吃光预算**(本次实测的典型) | 明确提示"思考占满了长度上限",引导**调高 max_tokens / 关闭思考** |
| 2xx | **`length`** | **有(截断)** | 任意 | 🔴 **正文被截断** | 显示已有内容 + **「继续生成」**按钮 |
| 2xx | `content_filter` | 空 | 任意 | ⚠️ **被内容过滤拦截** | 提示被拦截,**不要重试**(重试还是会被拦) |
| 2xx | `tool_calls` | 空(正常) | 任意 | ✅ **模型要调工具**,`content` 空是**预期行为** | 走工具链路,不是错误 |
| 2xx | `null` + 流已结束 | 任意 | 任意 | 🔴 **断流**(服务端中断/网络断) | 保留已收到的内容,给**继续/重试** |

**关键提醒:`finish_reason == "tool_calls"` 时 `content` 为空是完全正常的。**
这一行如果漏了,将来做 `E-2`(Function Calling 双通道)时会把每一次正常的工具调用都当成错误。

#### 流式下怎么判

流式更麻烦,因为 `finish_reason` 只在**最后一个 chunk** 里。实现要点:

```
维护三个累加器:  contentBuf / reasoningBuf / finishReason
维护两个标志位:  receivedAnyContentDelta / streamEndedNormally

对每个 chunk:
    delta.content        非空 → contentBuf += ;  receivedAnyContentDelta = true
    delta.reasoning_content / delta.reasoning  非空 → reasoningBuf +=
    choices[0].finish_reason 非 null → finishReason = 它
    收到 "[DONE]" 或流正常关闭 → streamEndedNormally = true

流结束后,按上面的矩阵判定。
注意:  content 为 null 和 "" 都要当作"无内容",不能只判 null(§3.4)
```

> ⭐ **一个必须避免的 bug:** 用 "第一个带 `content` 的 chunk" 作为"开始回答"的信号时,
> OpenRouter 的思考阶段 `content` 是 `""` 而**不是** `null`,
> 若用 `content != null` 判断,**UI 会在模型刚开始思考时就显示"正在回复"然后卡住几十秒**。
> 判据必须是 **`content` 非空字符串**。

#### 为什么会"有 content 还被截断"(你说的第三轮)

因为 `max_tokens: 1024` 是硬上限。第三轮 `prompt_tokens: 2132`、`completion_tokens: 1024` 全部用满,
模型话说到一半就被强制切断,于是 `content` 停在 `"以下为你细化的《三"` ——**句子中间断掉**。
这不是 bug,是预算问题。解法见 §4.3。

### 5.4 Q4 ⭐ ·「只知道用户的 apiKey,怎么知道他用的哪个平台?」

**先看竞品怎么做的(遵守铁律 1):**

| 竞品 | 做法 | 证据等级 |
|---|---|---|
| **SillyTavern** | **不自动探测。** 用户在 "Chat Completion Source" 下拉里**显式选择**(OpenAI / Claude / OpenRouter / DeepSeek / MistralAI / Custom (OpenAI-compatible) / …),选完才出现对应的 Key 与可选的 Proxy URL 输入框 | **E2**(公开 UI,本轮未实机核对) |
| **ChatterUI** | **不自动探测。** 用户新建 API 配置时先选模板,自定义端点则要手填 endpoint 并配置响应字段的取值路径 | **E2**(我的记忆,需核实) |

**→ 行业标准答案:根本不探测,让用户显式选。**

**我的建议方案(三层,E1 推导为主):**

| 层 | 做法 | 作用 |
|---|---|---|
| **第 1 层(主):预置 Provider 模板** | 内置 DeepSeek / OpenRouter / OpenAI / Anthropic / 自定义 等模板,每个模板固化 base_url 默认值、鉴权头格式、请求/响应方言、思考字段名。**用户选一个即可** | 这是**唯一可靠的判定依据** |
| **第 2 层(辅助体验):按 Key 前缀"猜"并预选** | 见下表。**只用于把下拉框预选到某一项 + 提示"看起来像 OpenRouter 的 Key"**,用户可改。**绝不作为判定依据** | 降低填写成本 |
| **第 3 层(校验):握手探测** | 用户填完点"测试连接",我们发一次最便宜的探测请求,成功则记下实际行为(是否返回 `reasoning`、是否支持 `stream_options`)并缓存进该配置 | **把"猜"变成"实测"** |

**Key 前缀表(前两行 E1,来自你的实测):**

| 前缀 | 大概率是 | 可靠性 |
|---|---|---|
| `sk-or-v1-` + 64 hex | **OpenRouter** | 🟢 高(前缀含厂商标识) |
| `sk-` + 32 hex | DeepSeek | 🔴 **低——和 OpenAI 旧格式完全撞车,无法区分** |
| `sk-ant-` | Anthropic (E2) | 🟢 高 |
| `AIza` | Google (E2) | 🟢 高 |

> **结论:前缀探测只能当"猜",不能当"判定"。** 因为 `sk-` 这个最常见的前缀本身就是歧义的,
> 而且**中转服务会自己发 `sk-` 开头的 Key**,前缀和真实上游毫无关系。

**第 3 层"握手探测"的具体建议(E2,待 `V-10` 验证):**

| 平台 | 探测端点 | 额外收益 |
|---|---|---|
| OpenRouter | `GET /api/v1/key` | 据称会返回该 Key 的额度/用量,**可以直接做成"余额"显示** |
| OpenRouter | `GET /api/v1/models` | 拉真实模型列表,**省掉让用户手打 model id** |
| DeepSeek | `GET /models` | 同上 |

> ⭐ **这是个产品机会:** 竞品(ST/ChatterUI)普遍要用户**手打 model id**,
> 打错了就报一个看不懂的 404。如果我们能"填完 Key 自动拉出模型下拉列表",
> 这是**首次配置体验上一个很具体的、低成本的超越点**。
> 对应 `R-P1`(BYOK 门槛导致流失)的直接缓解。
> → 建议登记为新需求 `A-12 · Key 握手与模型列表自动发现`,**优先级 P1**。

**最后回答你问题的后半句「万一不同平台结构有区别怎么处理」:**

本次实测已经证明**确实有区别**(§3)。处理方式是 A 模块做**双向归一化**:

```
请求侧:  统一的内部请求模型  →  [Provider 方言适配器]  →  各家实际 JSON
         (关 thinking 这个意图,在 DeepSeek 译成 thinking:{type:disabled},
          在 OpenRouter 译成 reasoning:{enabled:false})

响应侧:  各家实际 JSON  →  [Provider 方言适配器]  →  统一的内部响应模型
         (reasoning_content / reasoning / reasoning_details  都归一到  内部的 reasoning 字段)
```

**统一内部响应模型至少要有这些字段**(由本次实测反推):

| 字段 | 来源 |
|---|---|
| `content: String?` | `choices[0].message.content` / `delta.content`(`null` 与 `""` 统一成 `null`) |
| `reasoning: String?` | `reasoning_content`(DS)/ `reasoning`(OR) |
| `finishReason: FinishReason` | 枚举:`STOP` / `LENGTH` / `TOOL_CALLS` / `CONTENT_FILTER` / `ABORTED` / `UNKNOWN` |
| `nativeFinishReason: String?` | 原始值,用于诊断 |
| `usage.promptTokens / completionTokens / reasoningTokens / cachedTokens` | 两家字段路径不同 |
| `usage.costUsd: Double?` | OpenRouter 直接给;其余为 `null` |
| `providerName: String?` | OpenRouter 的 `provider`,诊断与评测固定用 |

---

## 6 · 本次实测对既有文档的 7 项修订

| # | 文档 | 原内容 | 修订 | 依据 |
|---|---|---|---|---|
| 1 | `docs/12 §2.3` | 示例 base_url 写 `https://api.deepseek.com/v1` | DeepSeek 官方示例是**不带 `/v1`**;App 的 URL 拼接必须容错(带不带 `/v1` 都要能用) | §2.1 |
| 2 | `docs/02` A 模块 | 响应归一化列为"建议" | **升级为 P0 必做**,并给出统一响应模型字段清单 | §5.4 |
| 3 | `docs/02` / `docs/05` | 未涉及 reasoning 预算 | 新增:默认关 reasoning、`max_tokens` 默认不传、下限 2048、`length` 必须显式提示、Continue 能力 | §4.3 |
| 4 | `docs/05` Prompt 装配 | 未考虑前缀缓存 | DeepSeek 有 `prompt_cache_hit_tokens`。装配顺序应为 **[稳定前缀:预设 + 角色卡 + 世界书] + [易变尾部:近期消息 + 状态块]**,让前缀可命中缓存。**这与"状态块靠近末尾以获得近因效应"的诉求恰好一致**,两个目标不冲突 | §2.2 |
| 5 | `docs/08` 评测方案 | 未考虑 provider 漂移 | 评测脚本**必须固定 provider**(`allow_fallbacks: false`),否则数据无意义 | §3.3 |
| 6 | `docs/09` | — | 新增 `R-T15`(reasoning 吃预算)/ `R-T16`(provider 漂移)/ `R-T17`(token 统计不自洽);`R-T1` 概率由"高"**确认为已发生** | §3、§4 |
| 7 | `docs/07` §1 | 泄露点列了代码/日志/git | **补第 4 个通道:人工整理的文档/截图/PDF** —— 本次就是从这里泄的 | §0 |

**另新增需求:`A-12 · Key 握手与模型列表自动发现`(P1)** —— 见 §5.4。

---

## 7 · 下一步:还必须补的测试

### 7.1 🔴 阻塞级(影响 Android 端实现)

| ID | 要测什么 | 怎么测 | 为什么阻塞 |
|---|---|---|---|
| **V-9** | **SSE 的结束标志**:是 `data: [DONE]` 还是直接断流?两家分别是什么? | `curl -N ... --raw` 把完整输出重定向到文件,看最后 5 行 | 解析器判"正常结束 vs 断流"的唯一依据 |
| **V-11** | **401 的响应体长什么样**(两家) | 故意把 Key 改错一个字符再跑 | `C-10` 错误处理矩阵要据此写文案 |
| **V-12** | **`stream: true` + `tools` 组合**(`R-T14`,M0 核心闸门) | `docs/12 §4.7` Step 4 | 决定 `E-2` 还是 `E-10` 当主方案,**直接改写 `docs/05`** |

### 7.2 🟡 重要

| ID | 要测什么 |
|---|---|
| **V-8** | OpenRouter `reasoning:{"enabled":false}` vs `{"exclude":true}` 的**计费差异**(关掉到底省不省钱) |
| **V-10** | `GET /api/v1/key`、`GET /api/v1/models` 的真实返回(决定 `A-12` 能不能做) |
| V-13 | 流式中途 Ctrl+C,服务端反应 + 是否继续计费 |
| V-14 | keep-alive / 注释行是否存在 |
| V-15 | 首 chunk 延迟(两家各 5 次取中位数) |
| V-16 | 固定 `provider` 后,同一 prompt 跑 3 次,输出稳定性如何 |

> **注意:V-7(竞品如何让用户选 Provider)已并入 `docs/12` T2-1/T2-2 的观察清单。**

### 7.3 ⭐ 以后记录测试的脱敏流程(避免再出 §0 的事)

**不要再手工复制 curl 到文档里。** 改用这个流程:

```bash
# 1. Key 只在环境变量里
export MIRAGE_DS_KEY="sk-..."

# 2. curl 里永远用变量,永远不写字面量
curl https://api.deepseek.com/chat/completions \
  -H "Authorization: Bearer $MIRAGE_DS_KEY" \
  -d @req.json | tee out.json

# 3. 整理文档前先过一遍脱敏
sed -E 's/(sk-[A-Za-z0-9_-]{6})[A-Za-z0-9_-]+/\1***REDACTED***/g' out.json > out.safe.json
```

**并且:把 `-H "Authorization: ..."` 那一行从文档里整行删掉,不要留掩码版。**
掩码版看着安全,但你这次就是"首页掩码、正文明文"——**只要有一处漏网,掩码就白做了**。
最稳的是:**文档里根本不出现 Authorization 这一行。**

---

## 8 · 变更日志

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-10-16 | v1.0 | 建档。基于用户第一次 API 实测(DeepSeek + OpenRouter),产出首批 E1 级证据;发现 reasoning 吃预算、provider 漂移、思考字段名不一致三个关键问题;回答用户 4 个问题;对既有文档提出 7 项修订 |

---

## 9 · 第二轮:探针首跑失败记录(2026-10-16 17:39)

> **结果:7 项探测 × 2 家 = 14 次全部失败,状态码全是 `-1`,`out/` 下的原始文件全是 0 字节或一行异常。**
> **但没有产生任何费用——因为请求根本没发出去。**

### 9.1 根因(一眼定位)

```
EXCEPTION: UnicodeEncodeError('latin-1', 'Bearer “sk-cb38***REDACTED***”', 7, 8, 'ordinal not in range(256)')
                                      ↑
                              第 8 个字符是 U+201C “
```

`export MIRAGE_DS_KEY=“sk-...”` 用的是**中文输入法的智能引号 `“ ”`(U+201C / U+201D)**,
而不是 ASCII 的 `"`。

- shell **不认** `“` 是引号字符 → 把它当成了 Key 值的一部分
- 于是 Key 变成了 `“sk-cb38…”`,首尾各多一个全角引号
- **HTTP 头必须是 latin-1 可编码的** → `urllib` 在拼 `Authorization` 头时直接抛异常
- 请求**从未离开本机**

### 9.2 这暴露了我的两个脚本缺陷(已修)

| 缺陷 | 后果 | 修复 |
|---|---|---|
| ① **入口不校验 Key** | 一个脏字符导致 14 次探测全部撞同一面墙 | 新增 `sanitize_key()` + `validate_key()`,**发请求前预检**。能自动修的(引号/空白/零宽字符)直接修掉并告知;修不了的**明确指出是第几个字符、是什么字符、怎么改** |
| ② **第一步失败后仍继续跑完剩余 6 项** | 报告里全是 `None`,噪音淹没真正的错误 | 新增**早停闸门**:非 200 立即跳过该服务商剩余探测,并在报告顶部单独列出失败原因 |

另修了一个脱敏副作用:`redact()` 原本用 `^.*authorization.*$` 整行删,
把报告里**自己那句"Authorization 整行移除"的说明文字**也吃掉了(所以首跑报告的表头缺了一行)。
已改为只匹配真正的头部形态。

### 9.3 ⭐ 一个意外的产品发现(E1,价值高于本次测试本身)

> **我们自己,一个有经验的开发者,在最简单的一步上被"粘贴 Key"绊倒了。
> 那么用户一定也会。而且用户看到的原始报错会是 `ordinal not in range(256)` 这种天书。**

这件事**直接验证**了 `docs/02 §5.1` "每种错误都必须带可操作按钮"的设计必要性,
并且补上了一个此前完全没想到的需求:**Key 输入必须在入口做净化与校验**。

**用户粘贴 API Key 时的真实污染源(按常见度排序):**

| # | 污染 | 来源 | 能否自动修 |
|---|---|---|---|
| 1 | **首尾引号**(含中文全角 `“ ” 「 」`) | 从教程/文档里连引号一起复制 | ✅ 自动剥掉 |
| 2 | **首尾空白 / 换行** | 从网页或邮件复制 | ✅ 自动 trim |
| 3 | **零宽字符 / 不换行空格**(U+200B、U+FEFF、U+00A0) | 从网页复制,**肉眼完全不可见** | ✅ 自动移除 |
| 4 | 中间有空格 | 复制时多选了内容 | ❌ 需报错,但要说清楚 |
| 5 | 前缀不符 / 长度异常 | 复制不全、被截断 | ⚠️ 警告但放行 |

**→ 新增需求 `A-16 · API Key 输入净化与即时校验`(P0)**,验收要点:

1. 输入框 **onPaste 即净化**:自动去首尾空白、剥成对引号(含全角)、移除零宽字符,**并在下方以浅色小字提示"已自动清理 N 处"**(不要静默修改,用户要知道发生了什么)
2. **即时格式校验**:长度、前缀、是否含非 ASCII/空格 → 在点"测试连接"**之前**就给出人话提示
3. 错误文案必须是**人话 + 可操作**:
   - ❌ `ordinal not in range(256)`
   - ✅ 「Key 里有一个全角引号(第 1 个字符),通常是复制时带进来的。[一键清理]」
4. 显示 **Key 指纹**(如 `sk-cb38… 共 35 字符`)让用户肉眼确认"这是我想用的那把",**而不是显示明文或纯掩码**

> 💡 **指纹这一条还有个额外好处**:本次首跑时,如果界面上显示了指纹,
> 就能当场发现**用的还是那两把已泄露的旧 Key**(见 §9.4)。

### 9.4 ⚠️ 本次附带发现:Key 尚未吊销

报告里的 Key 前缀是 `sk-cb38…` 与 `sk-or-v1-1d89…`,
**与 §0 记录的两把泄露 Key 完全一致** —— 说明 `T0`(吊销重建)**还没做**。

**重跑探针之前请先完成 T0。** 否则相当于拿一把已经公开的钥匙继续用。

### 9.5 本轮结论

| 项 | 状态 |
|---|---|
| V-8 / V-9 / V-10 / V-11 / V-13 / V-14 / V-15 | ❌ **全部仍未获得** —— 需用修复后的脚本重跑 |
| 费用 | ¥0(请求未发出) |
| 净收获 | ⭐ 新需求 `A-16`;脚本健壮性 +2;脱敏逻辑修正 1 处 |

---

## 10 · 第二轮:探针全绿(2026-10-16 17:55)**第二批 E1 证据**

> 原始报告:`out/REPORT.md`;原始产出:`out/{deepseek,openrouter}/01~07`。
> 被测模型:DeepSeek = `deepseek-flash`(DeepSeek-V4.1-Flash);OpenRouter = `z-ai/glm-5.1`。
> 7 个探测项**全部成功**,`§7.1` 的 3 个阻塞项与 `§7.2` 的 4 个重要项**全部关闭**。

### 10.0 本轮最重要的 5 条结论(按价值排序)

| # | 结论 | 级别 | 影响 |
|---|---|---|---|
| **C1** | **不显式关思考 = 贵 7.6 倍。** OR/GLM-5.1 默认 `reasoning_tokens=891`(占输出 87%)、`cost=$0.003119`;显式 `reasoning:{enabled:false}` 后 `reasoning_tokens=0`、`cost=$0.000411` | E1 | `docs/02` 必须强制下发关思考参数;`docs/05 §6.1` 的 E2 升 E1 |
| **C2** | **两家都有 `data: [DONE]`,而且 `[DONE]` 是唯一可靠的收尾判据。** OR 会发**两条** `finish_reason:"stop"` 的 chunk,`usage`/`cost` 只在**第二条**里 | E1 | 解析器**不得**在首个 `finish_reason != null` 时收尾,否则丢账单 |
| **C3** | **provider 漂移在同一次 60 秒测试内发生了 4 次。** 同一个 `z-ai/glm-5.1`,5 个请求命中 StreamLake / GMICloud / Venice / StreamLake / StreamLake | E1 | `R-T16` 升级为**方法论问题**:OR 上的任何单次测试结论都不可信,必须锁 provider |
| **C4** | **思维链字段名三套并存**:DS 用 `message.reasoning_content`,OR 用 `message.reasoning` + `reasoning_details`(流式 delta 同名) | E1 | `docs/02 §5.1.1` 归一化层必须三者都认 |
| **C5** | **首 token 可达 12.57 秒,且前 11 秒连心跳都没有。** OR 在 11.09s 才开始发 `: OPENROUTER PROCESSING` 心跳,12.57s 才出首字 | E1 | `FIRST_TOKEN` 超时必须 ≥30s;进度反馈必须做**三态** |

---

### 10.1 V-9 · SSE 结束标志 —— ✅ **阻塞解除**

| 服务商 | 结束哨兵 | data 行 | 注释/心跳行 | 空行 |
|---|---|---|---|---|
| deepseek | ✅ `data: [DONE]` | 148 | **0** | 148 |
| openrouter | ✅ `data: [DONE]` | 86 | **4** | 90 |

**→ 撤销原先"不得假设 `[DONE]` 存在"的警告。** 但新增三条更硬的要求:

1. **必须跳过注释行。** OR 的 4 行长这样(SSE 注释语法,冒号开头):
   ```
   : OPENROUTER PROCESSING
   ```
   DeepSeek 一条都没有 → **这是真实的服务商差异,不是边界情况。**
2. **必须以 `[DONE]` 为收尾,不能以 `finish_reason` 为收尾。** 实测 `finish_reason` 计数:
   | 服务商 | `null` | `"stop"` | 带 `usage` 的 chunk |
   |---|---|---|---|
   | deepseek | 146 | **1** | 1(最后一条) |
   | openrouter | 83 | **2** ⚠️ | 1(最后一条) |

   OR 的倒数第三条就已经是 `finish_reason:"stop"` 了,但 `usage`/`cost` 在倒数第二条。
   **如果解析器看到第一个 `stop` 就 break,账单数据直接丢。**
3. `data:` 与 `[DONE]` 之间**有一个空格**,且每条 data 后跟一个空行 → 解析时 `trim` 后再比较。

### 10.2 V-15 · 延迟 —— 差距 38 倍,是一级体验问题

| 服务商 | 首 chunk | 流总耗时 | 非流式总耗时 |
|---|---|---|---|
| deepseek | **331 ms** | 2.01 s | 4.05 s |
| openrouter | **12 570 ms** 🔴 | 16.04 s | 22.37 s |

OR 的时间线(来自 `03_stream_raw.txt` 的毫秒戳):

```
0 ──────────── 11 091ms          12 570ms ──────── 16 033ms
 ↑ 完全静默,0 字节   ↑ 心跳开始(每~420ms)  ↑ 首个正文 token   ↑ [DONE]
```

**三条设计结论:**

- **不能用"收到任何字节"做保活判据** —— 前 11 秒连心跳都没有。超时必须是绝对时长(≥30s),不是"空闲时长"。
- **进度反馈必须三态**:`连接中` → `服务商排队中`(收到心跳即切换) → `正在生成`(收到首个 content delta)。只做一个转圈,用户在第 8 秒就会以为卡死并重试,**重试会双倍计费**。
- **DeepSeek 做评测主力**(331ms vs 12.5s,38 倍),OR 只做兼容性验证。

### 10.3 V-8 · 思维链开关 —— ✅ 关闭,结论比预想更重要

完整四组对照(同一 prompt「写约150字·雨中废弃车站」):

| 服务商 | 思考参数 | prompt | completion | reasoning | cost | 耗时 | provider |
|---|---|---|---|---|---|---|---|
| deepseek | **不传(默认)** | 17 | 125 | — | — | 4.05s | — |
| deepseek | `thinking:{type:"enabled"}` | **43** | 161 | 36 | — | 1.70s | — |
| deepseek | `thinking:{type:"disabled"}` | 17 | 119 | **None** | — | 1.48s | — |
| openrouter | **不传(默认)** | 17 | **1022** | **891** 🔴 | **$0.003119** | 22.37s | StreamLake |
| openrouter | `reasoning:{enabled:true}` | 24 | 162 | 24 | $0.000747 | 5.40s | **Venice** |
| openrouter | `reasoning:{enabled:false}` | 17 | 130 | **0** | **$0.000411** | 16.52s | StreamLake |

> ⚠️ **2026-10-16 更正(见 §12.4):上表第 1 行「deepseek · 不传(默认) · reasoning = —」
> 据此得出的「DeepSeek 默认不思考」结论是错的。** 第三轮 3/3 个场景全部返回了 `reasoning_content`。
> 原记录保留以备溯源,但**不要再引用那条结论**。正确结论:**两家都默认开思考。**

**C1(E1):`reasoning:{enabled:false}` 是真关闭,不是"不返回照样计费"。**

但真正该记住的不是报告里那个「降 45%」(那是 on-vs-off,而 on 那次恰好只思考了 24 token):

> **默认($0.003119) vs 显式关闭($0.000411) = 贵 7.6 倍。**
> 默认行为下 891/1022 = **87% 的输出 token 花在用户看不见的思考上**,正文只有 ~131 token。

**→ 架构硬约束:请求体必须**显式**下发关思考参数,"不传"是最贵的选项。**

**两个衍生发现:**

- **参数名完全不同**,不存在通用写法:DS 用 `thinking:{type:"enabled"|"disabled"}`,OR 用 `reasoning:{enabled:bool}` → 归一化层需要 per-provider 参数映射表(见 §10.7)。
- **开思考时平台往 prompt 侧注入内容**:DS 的 `prompt_tokens` 从 17 → **43**(+26 token),OR 从 17 → 24(+7)。含义有两条:
  1. token 预算估算**不能只算自己拼的 prompt**,要留 buffer;
  2. 注入内容改变了 prompt → **会打断前缀缓存**。DS 的 `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens` 本轮全是 `0 / 17`,长会话省钱的前提是 prompt 前缀稳定,**思考开关来回切会让缓存永远不命中**。

- `off` 反而比 `on` 慢(16.52s vs 5.40s)**不是关思考的代价**,是 provider 从 Venice 漂到 StreamLake。见 §10.4。

### 10.4 🔴 C3 · provider 漂移升级为方法论问题(`R-T16`,E1)

同一次探针运行(约 60 秒内)、同一个 model id `z-ai/glm-5.1`,5 个请求命中了 **4 个不同的上游**:

| 产出文件 | 用途 | `provider` |
|---|---|---|
| `01_nonstream.json` | 非流式 | StreamLake |
| `03_stream_raw.txt` | 流式 | **GMICloud** |
| `05_reasoning_on.json` | 开思考 | **Venice** |
| `05_reasoning_off.json` | 关思考 | StreamLake |
| `06_starved.json` | max_tokens 饿死 | StreamLake |

**这不是"偶尔漂移",这是每次请求都在抽签。** 四条后果:

1. **OR 上的任何单次测试结论都不可信。** 包括我们自己的:`§10.3` 里 on/off 的耗时对比被污染了,`§10.5` 里 OR 被饿死而 DS 没有,也可能有 provider 成分。
2. 🔴 **这直接改变 V-12(`stream` + `tools` 是否可靠)的测法。** 原计划"在 OR 上测一次"**无效** —— 不同 provider 对 `tools` 的支持度不同,测中一个支持的就会得出错误的乐观结论。
   **V-12 必须:(a) 在 DeepSeek 上测(单一上游,无漂移);(b) 在 OR 上测时必须锁 provider 并重复 ≥3 次。**
3. **锁 provider 的写法**(待实测,E2):
   ```json
   "provider": { "order": ["StreamLake"], "allow_fallbacks": false }
   ```
4. **产品含义(可直接做成功能):每条消息都要记录 `provider` + `native_finish_reason`。**
   用户抱怨"今天它变傻了"时,这是唯一能自证的证据;竞品(ChatterUI/酒馆)都没做这件事。
   → 已作为建议写入 `docs/13`。

### 10.5 R-T15 复现 —— 复现了,而且触发条件更精确了

`max_tokens=256` + 开思考:

| 服务商 | finish_reason | content | completion | reasoning | cost |
|---|---|---|---|---|---|
| deepseek | `stop` | ✅ 正常(189 token 正文) | 189 | **40** | — |
| openrouter | **`length`** | 🔴 **空字符串** | 256 | **255** | $0.000794 |

**第一轮的 `R-T15` 完全复现:GLM 的 255 个 reasoning token 吃光了 256 的预算,正文一个字都没有,而且照样计费。**

但新增一条更重要的精确化:

> **DeepSeek 没被饿死,不是因为 DeepSeek 更安全,是因为 `deepseek-flash` 这次只思考了 36~40 token,而 GLM 思考了 255+。**
> 触发条件是**「模型的思考长度不可控且跨模型差两个数量级」**,不是 max_tokens 本身。

**→ 两个推论:**
- **"我测了一个模型没事"不能推广到其他模型。** 必须按模型维护思考预算经验值,或者干脆执行既有铁律:**默认不传 `max_tokens` + 默认关思考**。
- `A-13`(截断可感知 + 继续生成)**不可省**。因为无论怎么调参,只要用户自己选了个思考很长的模型就会踩到,而我们管不了用户选什么模型。

### 10.6 V-11 · 401 响应体 —— ✅ `C-10` 可以定稿

```json
// deepseek — HTTP 401
{"error":{"message":"Authentication Fails, Your api key: ****0000 is invalid (request_id: 55651bb6-…)","type":"authentication_error","param":null,"code":"invalid_request_error"}}

// openrouter — HTTP 401
{"error":{"message":"User not found.","code":401}}
```

| 观察 | 结论 |
|---|---|
| DS 的 `error.code` 是**字符串** `"invalid_request_error"`,OR 的是**数字** `401` | 🔴 **同名字段类型冲突,强类型反序列化会直接炸。** `error` 整体必须按宽松结构解析(`JsonElement` 兜底 + 逐字段 try) |
| OR 的文案是 `"User not found."` | 对用户**毫无帮助**。印证"不得把服务商原文抛给用户",必须按 **HTTP 码**映射我们自己的中文文案 |
| DS 自带 `request_id` | 诊断包(`docs/02 §5.12`)应抓取;OR 没有,但有 `id: gen-…` 可替代 |
| DS 的 `message` 里**回显了 Key 尾号** `****0000` | 脱敏时**连错误文案一起过 `redact()`**,不能只过请求头 |

### 10.7 V-10 · 模型列表 —— ✅ `A-4` 可行,而且能**自动探测能力**

| 服务商 | HTTP | 条目数 | 文件大小 |
|---|---|---|---|
| deepseek | 200 | **2** | 743 B |
| openrouter | 200 | **465** | **777 KB** |

**两种极端都要兼顾:2 个 vs 465 个。** 对 `A-4` 的硬性要求:

- 465 条 **不能用朴素下拉框**,必须**搜索 + 分组(按厂商) + 收藏置顶**;
- 777 KB 响应**不能每次打开选择器都拉**,必须本地缓存 + 手动刷新;
- 2 条的情况下搜索框应自动隐藏。

**⭐ 意外收获:两家的模型列表都返回了结构化能力声明,可以直接驱动 UI 和兜底逻辑。**

OpenRouter 每条带 `supported_parameters`:
```json
"supported_parameters": ["include_reasoning","max_completion_tokens","max_tokens","reasoning","temperature","top_p"],
"default_parameters": {"temperature":0.2,"top_p":0.9},
"top_provider": {"context_length":32000,"max_completion_tokens":4096,"is_moderated":false},
"architecture": {"modality":"text+image->text","input_modalities":["text","image"]},
"pricing": {"prompt":"0.0000003","completion":"0.0000025"}
```

DeepSeek 每条带:
```json
{"id":"deepseek-flash","name":"DeepSeek-V4.1-Flash",
 "context_window":1048576,"max_output_tokens":393216,
 "input_modalities":["text","image"],
 "effort":{"supported_levels":["low","high","max"],"default_level":"high"}}
```

**四条可直接落地的用法:**

| 用途 | 做法 | 价值 |
|---|---|---|
| **tools 能力预筛** | 读 OR 的 `supported_parameters` 是否含 `"tools"` | **可能让 V-12 的人肉测试降级为抽样验证** —— 但注意这是 OR 的**聚合**声明,provider 漂移下单个上游未必支持,仍需运行时兜底 |
| **上下文窗口自动填** | `context_length`(OR)/ `context_window`(DS) | 省掉用户手填,也让 token 预算有真实上限。注意 DS 是 **1 048 576**,OR 这条是 32 000,差 32 倍 |
| **成本预估** | `pricing.prompt` / `pricing.completion` | 发消息前能估价,竞品都没做 |
| ~~**思考档位 UI**~~ | ~~DS 的 `effort.supported_levels: [low,high,max]`~~ | 🔴 **本条已于 §12.5 撤回**:实测三档的 `reasoning_tokens` 无递增趋势(low=90 / high=64 / max=76),做成 UI 等于给用户一个假旋钮。仅保留 **on/off 二元 + 默认 off**。(样本仅 1 次/档且用的是 flash,降级为 `V-22` 待大样本复测)|

**per-provider 参数映射表(实测,写进 `docs/02 §5.1.1`):**

| 语义 | DeepSeek | OpenRouter |
|---|---|---|
| 开思考 | `"thinking":{"type":"enabled"}` | `"reasoning":{"enabled":true}` |
| 关思考 | `"thinking":{"type":"disabled"}` | `"reasoning":{"enabled":false}` |
| 思考强度 | `effort`:`low`/`high`/`max`(默认 **high**)⚠️ 三档实测**无效**,见 §12.5 | `reasoning.effort`(待测,E3) |
| 思考文本字段(非流式) | `message.reasoning_content` | `message.reasoning` + `reasoning_details` |
| 思考文本字段(流式 delta) | `delta.reasoning_content` ✅ **已实测确认(§12.7),与非流式同名** | `delta.reasoning` + `delta.reasoning_details` |
| 思考 token 计数 | `usage.completion_tokens_details.reasoning_tokens` | 同上 |
| 缓存命中 | `usage.prompt_cache_hit_tokens` / `_miss_tokens`(非标) | `usage.prompt_tokens_details.cached_tokens` / `cache_write_tokens` |
| 账单 | ❌ 无 | `usage.cost` + `usage.cost_details.upstream_*` |
| 上游真实结束原因 | ❌ 无 | `choices[].native_finish_reason` |
| 服务上游标识 | `system_fingerprint` | `provider` |

### 10.8 V-13 · 中断 —— 连接层 ✅,计费层 ⬜ 仍需人工核对

| 服务商 | 已读 data 行 | 断开耗时 |
|---|---|---|
| deepseek | 5 | 1 032 ms |
| openrouter | 5 | 3 317 ms |

断开本身干净可靠。**但"中断后是否继续计费"脚本测不出来**,必须到平台用量页核对这两笔请求的 `completion_tokens`:
- 若 ≈ 5 行对应的量 → 中断省钱 → `A-13` 的"停止生成"是真省钱按钮;
- 若 ≈ 完整回复 → 中断不省钱 → UI 不能暗示"停止=省钱",避免用户误解。

→ 仍挂在待办(`V-13b`)。

---

### 10.9 对既有文档的修订清单(本轮)

| # | 文档 | 修订 |
|---|---|---|
| 1 | `docs/02 §5.1.1` | 新增 §10.7 的 per-provider 参数映射表;思考开关由二态改**三态**(on/off/effort 档位);`error` 对象按宽松结构解析(`code` 字段类型冲突) |
| 2 | `docs/02` SSE 章节 | 解除 `[DONE]` 警告;新增**必须跳过 `:` 注释行**;新增**必须读到 `[DONE]` 才收尾(OR 有两条 stop,usage 在第二条)**;`FIRST_TOKEN` 超时 →**≥30s** |
| 3 | `docs/02` 请求构造 | **强制显式下发关思考参数**("不传"贵 7.6 倍) |
| 4 | `docs/02 §5.12` | 诊断包抓取 `request_id`(DS)/ `id`(OR)/ `provider` / `native_finish_reason` |
| 5 | `docs/05 §6.1` | V-8 结论 E2 → **E1**,数字从"降 45%"改为"默认 vs 关闭差 **7.6 倍**" |
| 6 | `docs/08 §1.5` | 注释行跳过 / `[DONE]` 存在 / 双 `stop` chunk → 从推测改 **E1 必测用例** |
| 7 | `docs/09` | `R-T15` 补第二轮证据 + 触发条件精确化;`R-T16` **升级为方法论级**;新增 `R-T17` 首 token 延迟 12.5s;`V-8/V-9/V-10/V-11/V-13a/V-14/V-15` 关闭 |
| 8 | `docs/01 A-4` | 验收补"465 条 → 必须搜索/分组/缓存";新增"从 `supported_parameters` 自动探测能力、自动填上下文窗口、显示单价" |
| 9 | `docs/01 A-13` | 标注**不可省**,理由见 §10.5 |
| 10 | `docs/12 §4.7 Step4` | **V-12 测法修正**:DS 上测 + OR 上锁 provider 重复 ≥3 次 |
| 11 | `docs/13` | 新增建议:每条消息记录 `provider`/`native_finish_reason` 作为"自证"能力 |
| 12 | `AGENTS.md` | 撤销 `[DONE]` 禁令;新增"不传思考参数 = 贵 7.6 倍"与"OR 单次结论不可信" |

### 10.10 本轮关闭/新增的编号

**关闭:** `V-8` `V-9` `V-10` `V-11` `V-14` `V-15` `V-13a`(连接层)

**仍阻塞:** `V-1`(酒馆变量真实格式) · `V-12`(`stream`+`tools`,**M0 核心闸门,测法已修正**)

**新增:**

| 编号 | 内容 | 优先级 |
|---|---|---|
| `V-13b` | 中断后是否继续计费(平台用量页人工核对) | 🟡 |
| `V-16` | DS 不传思考参数时实测**没有** `reasoning_content`,但 `/models` 声明 `default_level:"high"` —— 默认到底思不思考? | 🟡 |
| `V-17` | OR `provider:{order,allow_fallbacks:false}` 锁定是否真生效 | 🔴(V-12 前置) |
| `V-18` | DS 流式 delta 的思考字段是否为 `reasoning_content`(本轮流式未开思考,未取到样本) | 🟡 |
| `V-19` | `effort:"low"/"high"/"max"` 三档的 token 与成本差异 | 🟡 |

---

## 11 · 零成本追加:tools 能力普及度(`V-12b`,2026-10-16,E1)

> **没有发任何请求**,只是把第二轮已经下载下来的 `out/openrouter/07_models.json`(777KB / 465 条)
> 按 `supported_parameters` 过了一遍(`tools/m0_probe2.py --no-net`)。

| 项 | 值 |
|---|---|
| 模型总数 | 465 |
| **声明支持 `tools`** | **394(85%)** |
| 不支持 | 71(15%) |
| **免费且支持 tools** | **15** |
| 我们配置的 `z-ai/glm-5.1` | ✅ 支持 |

### 11.1 这推翻了 `docs/12 §4.7` 的一条假设

原文写的是:

> 方案 A 的缺点:**很多小服务商不支持 tools**

**实测:在 OpenRouter 的 465 个模型里,85% 声明支持 tools,连最便宜的那一档也普遍支持。**

最便宜的 15 个支持 tools 的模型(completion 单价,美元/token):

| 模型 | 上下文 | completion 单价 | 也支持 reasoning? |
|---|---|---|---|
| `mistralai/mistral-nemo` | 131 072 | 0.000000030 | 否 |
| `inclusionai/ling-3.0-flash` | 262 144 | 0.000000063 | 是 |
| `meta-llama/llama-3.1-8b-instruct` | 131 072 | 0.000000080 | 否 |
| `openai/gpt-oss-20b` | 131 072 | 0.000000090 | 是 |
| `mistralai/ministral-3b-2512` | 131 072 | 0.000000100 | 否 |
| `qwen/qwen3.7-flash` | **1 000 000** | 0.000000130 | 是 |
| `amazon/nova-micro-v1` | 128 000 | 0.000000140 | 否 |
| `qwen/qwen3.5-9b` | 256 000 | 0.000000150 | 是 |

**⭐ 免费且支持 tools 的 15 个**(可用于**零成本大批量回归**,这对 `docs/08` 的评测集是意外之喜):
`nvidia/nemotron-3.5-super-vl:free`、`nvidia/nemotron-3.5-lightning:free`、
`liquid/lfm-2.5-2.6b:free`、`thinkingmachines/inkling:free`、`thinkingmachines/inkling-small:free`、
`poolside/laguna-s-2.1:free`、`poolside/laguna-xs-2.1:free`、`google/gemma-4-26b-a4b-it:free`、
`google/gemma-4-31b-it:free`、`cohere/north-mini-code:free`、`dots-studio/dots-3-note-preview:free`、
`nvidia/nemotron-3-super-120b-a12b:free`、`nvidia/nemotron-3-ultra-550b-a55b:free`、
`nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free`、`openrouter/free`

### 11.2 但**不能**就此把方案 A 定为主方案,三条保留意见

| # | 保留意见 | 依据 |
|---|---|---|
| 1 | `supported_parameters` 是**聚合商的聚合声明**,不等于实际命中的 provider 支持 | `R-T16` provider 漂移(§10.4)→ 运行时兜底不可省(`docs/02 §5.1.2b` P4) |
| 2 | **"声明支持 tools" ≠ "`stream` + `tools` 一起用还正常"**,后者才是我们的真实用法 | 这正是 `V-12a` 要测的,`tools/m0_probe2.py` 已覆盖 |
| 3 | 这 85% 只是 **OpenRouter 生态**的数字。用户也会直连 DeepSeek / 本地 Ollama / 各种中转,**那些的分母完全不同** | `R-T18`(地区限制)、`docs/01` A 模块的多端点需求 |

**→ 结论(E1 的部分):"很多模型不支持 tools"这个**论据**不成立,应从 `docs/12 §4.7` 删除。
但"方案 A 为主"仍**不能现在拍** —— 真正的决定因素变成了 §11.2 的第 2 条(`stream`+`tools` 的实际行为)
和 `V-1`(方案 B 对齐行业格式后能达到什么水平)。**方案 A/B 的主次判断依据被收窄,但结论仍未定。**

### 11.3 顺带:这件事本身是个方法论提醒

**我们花了 0 元、0 次请求,就修正了一条写进执行清单的假设。**
原因只是上一轮顺手把 `/models` 整个存了下来。
→ **以后的探针脚本一律把原始响应完整落盘**(已是 `m0_probe.py` 的既有行为,继续保持)。
很多问题的答案已经在我们手里的数据里,只是还没去看。

---

## 12 · 第三轮:探针 #2(2026-10-16 20:24)**第三批 E1 证据**

> 脚本:`tools/m0_probe2.py` · 原始产出:`out2/`(含 `REPORT2.md` + 每项的原始流/JSON)
> 本轮 **5 项关闭、1 项推翻上一轮的记录、1 项我的脚本有 bug**。

### 12.0 本轮最重要的 6 条结论(按价值排序)

| # | 结论 | 证据 | 影响 |
|---|---|---|---|
| 1 | **`provider` 锁定可靠(3/3 命中)** | §12.1 | `V-17` 关闭 → **`V-12`/Step 4 解除阻塞** |
| 2 | **stream + tools 完全可用,且叙事与工具调用在同一个流里共存** | §12.2 | **方案 A 技术风险排除**;「边播叙事边更新状态」可行 |
| 3 | 🔴 **锁定 provider 的代价是尾延迟:一次 59.7s** | §12.1 | `allow_fallbacks:false` **不能做 App 默认值** → 新 ADR |
| 4 | 🔴 **DeepSeek 不传思考参数时默认就在思考** | §12.4 | **推翻 §10.3 的记录**;R6 升级为「两家都要显式关」 |
| 5 | `effort` 三档在 flash 模型上**不是有效成本旋钮** | §12.5 | 撤回「思考深度三档 UI」建议 |
| 6 | 两家都有额度端点 | §12.6 | 新需求 `A-17`「剩余额度展示」 |

---

### 12.1 🔴 V-17 · provider 锁定 —— ✅ **生效,V-12 阻塞解除**

锁定写法(OpenRouter 专有,实测可用):
```json
"provider": {"order": ["Phala"], "allow_fallbacks": false}
```

| 组 | # | 实际 provider | 命中目标 | cost | 耗时 |
|---|---|---|---|---|---|
| 不锁 | 1 | `Phala` | — | 2.05e-05 | 2.6s |
| 不锁 | 2 | **`Nebius`** | — | 2.28e-05 | 3.1s |
| 不锁 | 3 | **`Friendli`** | — | 2.28e-05 | 4.6s |
| 锁定 | 1 | `Phala` | ✅ | 2.05e-05 | **59.7s** 🔴 |
| 锁定 | 2 | `Phala` | ✅ | 2.05e-05 | 3.6s |
| 锁定 | 3 | `Phala` | ✅ | 2.05e-05 | 3.8s |

**C12(E1):锁定 3/3 命中 → 评测可复现,`V-12` 可以开跑。**

**C13(E1):上游池比想象的大得多。** 本轮命中的 3 个上游(Phala / Nebius / Friendli)与上一轮(§10.4)的 StreamLake / GMICloud / Venice **完全不重叠** → 两轮合计已见到 **6 个以上**不同上游,`R-T16`(provider 漂移)的严重性再次上调。

**C14(E1,新发现,也是本轮最有价值的副产品):🔴 锁定的代价是尾延迟。**

锁定组 3 次里有 1 次耗时 **59.7s**,另两次 3.6s / 3.8s;同样的 prompt 不锁时是 2.6~4.6s。
合理解释:`allow_fallbacks:false` 时,如果目标上游正忙,请求只能**排队**而不能换人。

> **→ 架构决策:`allow_fallbacks:false` 适合「评测 / 复现 bug」,绝不能做 App 默认值。**
> App 默认应当**允许 fallback,但记录实际命中的 provider**(`docs/13 §12` 的生成详情面板)。
> 已写成 ADR,见 `docs/02 §5.1.2b`。

---

### 12.2 🔴 V-12a · stream + tools 冒烟 —— ✅ **方案 A 技术上可用**

#### 非流式

| 项 | OpenRouter(锁 Phala) | DeepSeek |
|---|---|---|
| HTTP | 200 | 200 |
| `finish_reason` | `tool_calls`(native 同) | `tool_calls` |
| tool_calls 条数 | 1 | 1 |
| 工具名 | `update_state` | `update_state` |
| arguments 可解析 | ✅ | ✅ |
| arguments 原文 | `{"hp_delta": -5, "items_added": ["铜钥匙"], "items_removed": []}` | `{"hp_delta": -5, "items_added": ["铜钥匙"]}` |

**两家都在同一次响应里既写了叙事正文、又调了工具** —— 这是方案 A 的前提,成立。

#### 流式(重点)

| 项 | OpenRouter(锁 Phala) | DeepSeek | 差异含义 |
|---|---|---|---|
| 首 data 延迟 | **8754 ms** 🔴 | **215 ms** | **40 倍**,与 §10.2 的 38 倍一致 |
| 总耗时 | 12371 ms | 1535 ms | |
| data 行 / 注释行 | 89 / **7** | 99 / **0** | OR 发心跳注释行,DS 不发 |
| `[DONE]` | ✅ | ✅ | 与 §10.1 一致 |
| `finish_reason` 序列 | **`['tool_calls','tool_calls']`** | **`['tool_calls']`** | 🔴 OR 两条、DS 一条 |
| **正文 delta 片数** | **74** | **53** | 叙事确实是流式逐字出的 |
| **工具片段数** | **12** | **43** | 分片粒度差 3.6 倍 |
| **arguments 分片数** | **12** | **43** | 都必须跨 chunk 拼接 |
| 工具名出现次数 | 1 | 1 | **名字只在第一片** |
| `tool_call.index` | `[0]` | `[0]` | 归集靠它 |
| **拼接后可解析** | ✅ | ✅ | **方案 A 的生死线,过了** |
| 拼接结果 | `{"hp_delta": -5, "items_added": ["铜钥匙"]}` | `{"hp_delta": -5, "items_added": ["铜钥匙"], "flags": {"石门已推开": true, "飞镖陷阱已触发": true}}` | DS 还自己填了 `flags` |

**C15(E1):流式 tool_calls 的归集规则(写进 `docs/02`,四条都是实测逼出来的):**

1. **按 `index` 归集** —— 多工具并发时这是唯一的归属依据;
2. **`function.name` 只在第一片出现**,后续片只有 `arguments` → 解析器必须记住名字,不能每片都读;
3. **`arguments` 必须把所有片的字符串**原样拼接**后再 `JSON.parse`** —— 分片数在 12~43 之间波动,**绝不能假设任何固定片数**,也不能对中间态做解析尝试;
4. **只有看到 `[DONE]` 才允许解析** —— 结合 §10.1,这是唯一可靠收尾判据。

**C16(E1):上一轮的 S2 规则在 tools 场景同样成立,而且更危险。**
§10.1 记录过 OR 会发两条 `finish_reason:"stop"`;本轮两条是 `tool_calls`。
→ **解析器在读到第一个非空 `finish_reason` 时不得 `break`**;否则会丢掉第二条里的 `usage`,在 tools 场景下还可能丢掉后半截 `arguments`。

**C17(E1,产品含义):「边播叙事边更新状态」是真的能做。**
74 片正文 + 12 片工具调用在**同一个 SSE 流**里共存 → DZMM 那种「悬浮状态面板每轮自动更新」可以做成**真流式**:正文边打字,状态在流结束瞬间原子更新。这是 `docs/13` 的一个产品解锁。

**⚠️ 仍不能据此把方案 A 定为主方案,三条保留(与 §11.2 一致):**

1. 只测了 **2 个强模型**,没测便宜档 / 免费档 —— 而我们的用户大概率用那一档;
2. OR 首 data **8.7s** 仍然很差,tools 可能加重了首字延迟(无 tools 时是 §10.2 的数字);
3. `V-1` 未回,方案 B(对齐 SillyTavern 格式)的真实水平未知。

---

### 12.3 🔧 V-13b · **我的脚本有 bug,本项无效**

报告里 OpenRouter 侧的计费字段**全是 `None`**:

```
| 计费 completion token | None | None |
| total_cost            | $None | $None |
| cancelled 字段        | None  | None  |
```

**根因(已查明,不是「没数据」而是「查不到」):**

`out2/openrouter/v13b_full_generation.json` 的实际内容是:
```json
{"error":{"message":"Generation gen-1792153570-6FbdNemvCbLg9WhaQH4e not found","code":404}}
```

排查结论:
- `gen_id` 提取**正确**(与原始流里的 `id` 一致);
- `Authorization` 头**正确**(同一个 key 的其他端点都 200);
- → 所以是 **`GET /api/v1/generation?id=` 在我的轮询窗口内还查不到这笔**。
  我只轮询了 6 次 × 4s ≈ **20s**,对 OpenRouter 的落账延迟很可能不够。

**教训(第三条同类教训了):** 凡是「写入后读」的接口,轮询窗口不能凭感觉拍,
必须**先用一个旧 ID 做零成本探测**确定落账延迟量级,再决定窗口。

**修法(已实现为 `tools/m0_probe3.py`):**

| 步 | 做什么 | 成本 | 为什么 |
|---|---|---|---|
| S1 | 拿 `out2` 里那两个**几小时前**的 `gen_id` 重查 | **0** | 二选一定死根因:现在能查到=落账延迟;还 404=端点/权限问题 |
| S2 | `GET /api/v1/credits` + `GET /api/v1/key` | **0** | 关闭 V-20 的 OR 一半;`total_usage` 是高精度浮点 → 可当**独立测量工具** |
| S3 | credits 差值 + generation 长轮询(90s)双口径测「中断 vs 完整」 | ~$0.00006 | 两套证据互相校验,不再单靠 generation 端点 |

**另:DeepSeek 侧余额 Δ 全是 0.0(29.98 → 29.98 → 29.98)是预期结果,不是脚本坏。**
DS 余额精度到「分」,单笔成本远小于一分 → **DeepSeek 无法用余额反推单笔**。
要测只能用「N 次中断 vs N 次完整」的总额差,留到以后跑大批量时顺带做。

---

### 12.4 🔴 V-16 · DeepSeek 默认思考行为 —— **推翻 §10.3 的记录**

| 场景 | prompt 内容 | 有 `reasoning_content` | reasoning_tokens | prompt | completion |
|---|---|---|---|---|---|
| 简单 | 「只回复两个字:收到」 | ✅ **有** | **30** | **35** | 32 |
| 叙事 | 短叙事 | ✅ **有** | 19 | 43 | 92 |
| 推理 | 鸡兔同笼 | ✅ **有** | **72** | 59 | 81 |

#### ⚠️ 更正声明(不偷偷改,原记录保留)

> **§10.3 表格里「deepseek · 不传(默认) · reasoning = —」这一格的解读是错的。**
> 当时我据此写下「DeepSeek 默认不思考,`/models` 的 `default_level:high` 只是档位默认值而非默认开启」。
> **本轮三个场景全部返回了 `reasoning_content` → 该结论推翻。**
> `/models` 里的 `effort.default_level: "high"` 是**真的默认生效**。
>
> 原始差异待核:上一轮 `out/deepseek/01_nonstream.json` 确实没有 `reasoning_content`(completion=125)。
> 可能原因:① 上一轮那次走了不同的模型版本/路由;② 当时的 prompt 恰好触发了不思考路径。
> **处置:以本轮 3/3 为准(样本更多、场景更全),但标注「上一轮有 1 次反例,原因未明」。**

#### C18(E1)四条含义

1. **两家都默认开思考** —— 不是只有 OpenRouter。R6 必须升级(见 `docs/05 §6.1.1`)。
2. **连「回复两个字」都要花 30 个 reasoning token。** 简单场景 reasoning(30) ≈ completion(32)
   → **成本直接翻倍**,而且这部分用户完全看不见。
   对我们尤其致命:角色扮演 App 有大量「嗯」「好的」这类短回合。
3. **`prompt_tokens` 注入量比上一轮更大**:简单场景 35(上一轮同类 prompt 是 17)、推理场景 59
   → 再次证实 §10.3 的衍生发现:**思考开关会打断前缀缓存**,长会话省钱的前提被破坏。
4. **token 预算必须按「随时可能多花几十到几百 token」留余量** —— 不能只算自己拼的 prompt。

---

### 12.5 🟡 V-19 · `effort` 三档 —— **不是有效的成本旋钮**(撤回我上一轮的建议)

| effort | HTTP | reasoning_tokens | completion | 耗时 |
|---|---|---|---|---|
| `low` | 200 | **90** | 99 | 1.3s |
| `high` | 200 | **64** | 77 | 0.8s |
| `max` | 200 | **76** | 85 | 1.1s |

**三档全部 HTTP 200(参数被接受),但 `reasoning_tokens` 没有递增趋势,`low` 反而最高。**

> **→ 撤回 §10.7 里「思考档位 UI(做成 low/high/max 三档)」的建议。**
> 在 `deepseek-flash` 上,三档的差异更像随机波动,**做成 UI 会给用户一个假旋钮**。
> 只保留 **on/off 二元开关,且默认 off**。
>
> ⚠️ **保留意见,不彻底否决:** 样本量只有 **1 次/档**,且用的是 flash(小模型本来就不太需要深思考)。
> `deepseek-v4-pro` 上可能不同。**降级为「待大样本复测」**(每档 ≥5 次 + 换 pro 模型),
> 新编号 `V-22`,优先级低(不阻塞任何事)。

---

### 12.6 ⭐ V-20 · 额度端点 —— DeepSeek ✅,催生新需求 `A-17`

```
GET https://api.deepseek.com/user/balance  →  HTTP 200 · 0.2s
{"is_available": true, "balance_infos": [{"currency":"CNY",
  "total_balance":"29.98","granted_balance":"5.32","topped_up_balance":"24.66"}]}
```

| 项 | 值 |
|---|---|
| 响应时间 | **0.2s**(极快,可以在设置页直接同步展示) |
| 字段 | `total_balance` / `granted_balance`(赠金) / `topped_up_balance`(充值) |
| 精度 | 到「分」 → 够做展示,**不够做单笔计费分析** |

**C19(E1):BYOK 产品最该有、而竞品都没做的一个小功能 —— 「你还剩多少钱」。**

但必须按「可选能力」设计,理由是实测出来的:

| 服务商 | 端点 | 字段形状 | 币种 |
|---|---|---|---|
| DeepSeek | `GET /user/balance` | `balance_infos[].total_balance` | CNY |
| OpenRouter | `GET /api/v1/credits` | `total_credits - total_usage` | USD |
| 其他 | 很可能没有 | — | — |

→ **每个 Provider 模板各自实现 `fetchBalance(): Balance?`,返回 null 就不显示这个入口。**
绝不能当通用字段写进统一响应模型。新需求编号 **`A-17`**,见 `docs/01`。
OpenRouter 侧由 `m0_probe3.py` 的 S2 验证。

---

### 12.7 ✅ V-18 · DeepSeek 流式思考字段名 —— 确认 `reasoning_content`

```
delta 出现过的字段统计: {"role": 1, "content": 110, "reasoning_content": 110}
首 data 199ms · 注释行 0 · [DONE] ✅
```

**C20(E1):DeepSeek 流式与非流式同名,都是 `reasoning_content`。**
→ §10.7 映射表里「思考文本字段(流式 delta)」那一格的「待复测」已可填实。
→ 另外再次确认:**DS 不发 SSE 心跳注释行**(0 行),OR 发(本轮 7 行)。

---

### 12.8 本轮关闭/新增/更正的编号

| 动作 | 编号 | 说明 |
|---|---|---|
| ✅ 关闭 | `V-12a` | stream + tools 可用,方案 A 技术风险排除 |
| ✅ 关闭 | `V-16` | DS 默认**开**思考(推翻上一轮) |
| ✅ 关闭 | `V-17` | provider 锁定可靠 → **解除 `V-12` 阻塞** |
| ✅ 关闭 | `V-18` | DS 流式思考字段 = `reasoning_content` |
| ✅ 关闭 | `V-19` | effort 三档无效(降级为 `V-22` 待大样本复测) |
| ✅ 关闭 | `V-20`(DS 半) | 余额端点可用 |
| 🔧 待重跑 | `V-13b` | 我的脚本 bug,修法见 §12.3,新脚本 `m0_probe3.py` |
| 🆕 新增 | `V-21` | OpenRouter `/credits` + `/key` 端点验证(在 probe3 S2) |
| 🆕 新增 | `V-22` | effort 三档大样本复测(低优先级) |
| 🆕 新增 | `A-17` | 剩余额度展示(可选能力) |
| 🆕 新增 | `R-T24` | 锁定 provider 导致尾延迟(59.7s)⚠️ 初稿误编为 `R-T19`(已被第二轮占用),已改号 |
| 🆕 新增 | `R-T25` | 「不传思考参数」在所有服务商上都最贵(不只 OR) |
| 🆕 新增 | `R-T26` | 流式 `arguments` 分片数跨服务商差 3.6 倍 |
| 🆕 新增 | `R-T27` | 模型会自发输出 schema 外字段 |
| ⚠️ 更正 | §10.3 | 「DS 默认不思考」→ 错,已推翻,见 §12.4 |
| ⚠️ 撤回 | §10.7 | 「思考档位做成三档 UI」→ 撤回,见 §12.5 |

### 12.9 对既有文档的修订清单(本轮,已全部执行)

| 文档 | 修订 |
|---|---|
| `docs/01` | 新增 `A-17` 剩余额度展示 |
| `docs/02` | 新增 ADR-7(`allow_fallbacks` 不做默认值)+ §5.1.1c 流式 tool_calls 归集规则 |
| `docs/05` | R6 升级为「两家都默认开思考」;撤回思考三档 |
| `docs/09` | 关闭 `V-17`;`R-T16` 升级;新增 `R-T19` |
| `docs/12` | `V-12`/Step 4 解除阻塞,测法补「必须覆盖便宜/免费档」 |
| `docs/13` | §12 生成详情面板补字段;新增「状态面板可做真流式」 |
| `docs/00` | 进度、下一步、变更日志 |
| `AGENTS.md` | 第 5、6 条 E1 硬约束更新;新增第 8 条(tool_calls 归集) |

---

# §13 · 第四轮实测(`m0_probe3.py` / `out3/REPORT3.md`,2026-10-18)

| 项 | 值 |
|---|---|
| 脚本 | `tools/m0_probe3.py`(支持 `--only s1,s2` / `--settle N`) |
| 目的 | S1 复查上一轮 404 的根因 · S2 额度端点 · **S3 中断是否省钱** |
| 证据等级 | **E1** |

## 13.1 S1 · 上一轮的 404 是**落账延迟**,端点没问题(我的判断被证实)

上一轮(`§12.3`)查 `GET /api/v1/generation?id=` 20 秒内返回 404,我当时判断是"写入后读"的延迟而非端点错误。本轮用同样两个 `gen_id` 重查:

| 上一轮那次 | `gen_id` | 本轮 HTTP | 计费 completion | `total_cost` | `cancelled` |
|---|---|---|---|---|---|
| 完整请求 | `gen-1792153570-…` | **200 ✅** | 280 | $0.00126 | `False` |
| 中断请求 | `gen-1792153613-…` | **200 ✅** | **329** | $0.0014756 | `False` |

✅ 结论成立:**端点可用,只是落账慢。**
⭐ 但顺手暴露了一个当时没注意的事实:**那次"中断"的请求计了 329 token,比跑完整的 280 还多。** 这直接引出了 S3。

## 13.2 🔴🔴 S3 · **中断不省钱**(本轮最重要的结论)

同一个 prompt 跑两次,一次读完整个流,一次**只读 5 行 SSE 就断开 TCP**:

| 项 | 跑完整 | **读 5 行就断** |
|---|---|---|
| 客户端收到 SSE `data:` 行数 | 301 | **5** |
| 客户端感知耗时 | 20537 ms | **3481 ms** |
| 流内 `usage` | 有 | **无**(断太早) |
| **计费 completion token** | 300 | **315** 🔴 |
| **`total_cost`** | $0.0012842 | **$0.0013472** 🔴 |
| **credits 实际扣减** | 0.0012842 | **0.0013472** 🔴 |
| `cancelled` 字段 | `False` | **`False`** 🔴 |
| 后台 `generation_time` | 18379 ms | **20059 ms** 🔴 |
| 落账延迟(credits 变化) | 22.2 s | 43.7 s |

> 两套独立口径(`generation.total_cost` 与 `/credits` 差值)比值都是 **1.05**,交叉校验一致 —— 不是测量误差。

### 四条必须进设计的硬结论

1. 🔴 **客户端断开 TCP ≠ 上游停止生成。**
   客户端 3.5 秒就断了,后台仍然跑满 **20 秒**、生成 **315 token**、**全额计费**。断开只是"我不看了",钱照花,甚至因为没了下游背压反而**多生成了 5%**。

2. 🔴 **`cancelled` 字段不能用来检测"用户中断"。** 两次都是 `False`。它大概只在**通过 API 主动取消**时才为真。
   → 任何"统计用户中断次数/中断省了多少钱"的功能,**都不能基于这个字段**。

3. 🔴 **UI 绝不能暗示"停止生成 = 省钱"。**
   "停止"按钮的真实语义是**停止显示**,不是停止计费。文案必须诚实(例如"停止显示(本次仍会计费)")。
   → 控成本**只能**靠 `max_tokens`,而 `max_tokens` 又撞上 `§4` 的"思考吃光预算"(`R-T15`)。
   **这是一个真实的两难。** ✅ 它在 `docs/05 §4.0` 被解开了:叙事调用不设上限、结算调用设 512。

4. 🔴 **"用户误判卡死 → 重试 → 双倍计费"从 E2 推断升级为 E1 实测。**
   结合 `§12` 的"首 token 最长 12.57s、前 11s 零字节",用户完全可能以为卡死而重试,而第一次请求**仍在后台跑完并计费**。
   → `docs/02 §5.1.1b` 的 **S6(进度三态)优先级上调为 P0**,它不是体验优化,是**省钱功能**。

## 13.3 ⭐ S2 · 额度端点可用,且字段比预期丰富得多

```
GET /api/v1/credits → total_credits=10.0 · total_usage=0.046506138 · 余额=9.95349386(小数 9 位)
GET /api/v1/key     → limit=100 · limit_remaining=99.953493862 · usage=0.046506138
```

`/key` 返回 **44 个字段**,其中有产品价值的:

| 字段 | 内容 | 用途 |
|---|---|---|
| `budgets[]` / `effective_budget` | `limit_usd` / `spend_usd` / `remaining_usd` / `reset_interval` / `resets_at` | **预算进度条** |
| `usage_daily` / `usage_weekly` / `usage_monthly` | 分周期消费 | **消费趋势** |
| `free_model_daily_requests` | `{used:0, limit:1000, remaining:1000}` | **免费额度剩余** |
| `is_byok` / `limit_reset` | | 辅助 |

→ 🔴 **`A-17` 从"只显示余额"升级为"预算进度条 + 日/周/月消费 + 免费额度剩余"。三个竞品都没有这个。**(SillyTavern 无账单视图、ChatterUI 无、DZMM 是包月不透明)

`generation` 端点的 44 字段(供 `docs/13 §12` 详情面板选用):
`cache_discount` · `cancelled` · `finish_reason` · `generation_time` · `latency` · `moderation_latency` · `native_tokens_cached` · `native_tokens_reasoning` · `num_fetches` · `provider_name` · `router` · `service_tier` · `streamed` · `total_cost` · `upstream_id` · `upstream_inference_cost` · `is_byok`

## 13.4 ⚠️ 报告里有一个数字是假象,别引用

`REPORT3.md` 写 generation "0.0s 后查到" —— **这是测量顺序造成的假象**:脚本先轮询 credits 等了 22~44 秒,之后才去查 generation,所以 generation 自然"立刻"就有了。

✅ **真实落账延迟应以 credits 口径为准:22.2 s / 43.7 s。**
→ 上一轮用 20s 窗口查不到,是"差一点",不是"端点坏了"。
→ `§12` 第 9 条硬约束(成本数字不能即时显示)**加强**:轮询窗口至少 **60 秒**,且必须做成"稍后自动补全",不能让用户盯着转圈。

## 13.5 ⚠️ credit limit 与用户的认知不一致

用户称已把 OpenRouter credit limit 设成 **5**,但 `s2_key.json` 实测:

```json
"budgets": [{"scope":"key_limit","limit_usd":100,"spend_usd":0.046506138,"remaining_usd":99.953493862}]
```

`label` 显示就是那把测试 key。可能是:① 设在了别的 key 上;② 设的是别的层级;③ 探针跑完之后才设。
**验证方法(零成本、2 秒):`python3 tools/m0_probe3.py --only s2`,看 `limit_usd` 是不是 5。**
(缓解:账户 `total_credits` 只有 $10,所以真实损失上限是 $10 而非 $100。)

## 13.6 本轮关闭 / 变更

| 编号 | 变更 |
|---|---|
| `V-13b` | ✅ 关闭(额度端点可用且字段丰富) |
| `V-21` | ✅ 关闭(落账延迟实测 22.2s / 43.7s) |
| `R-T15` | 关联:两难已由 `docs/05 §4.0` 解开 |
| `R-T28` 🆕 | **中断不退款** —— 用户以为停止能省钱,实际全额计费甚至更贵 |
| `A-17` | 升级为预算进度条 + 日/周/月 + 免费额度 |
| `S6` | 优先级 → **P0**(省钱功能,非体验优化) |

## 13.7 ✅ credit limit 已确认 = $5(2026-10-18 复测,`--only s2`)

```
GET /api/v1/credits → total_credits=10.0 · total_usage=0.049137538 · 余额=9.95086246
GET /api/v1/key     → limit=5 · limit_remaining=4.950862462 · is_free_tier=False
```

→ **`limit=5` 已生效**,`§13.5` 的疑问关闭。原因应为"探针首次运行(15:21)时尚未设置,19:48 复测时已设"。
→ 当前风险敞口:**$5**(而非 $100,也低于账户余额 $10)。✅ 缓解措施到位。

> ⚠️ 旁注:本次 `--only s2` 运行**覆盖了 `out3/REPORT3.md`**,S1/S3 两节变成「未执行」。
> **但原始证据仍在**:`out3/openrouter/s1_requery_*.json`、`s3_*_generation.json`、`s3_full_raw.txt`(87KB)均保留。
> §13.1~13.4 的结论基于这些原始文件,**不受报告被覆盖影响**。
> → 📌 **给脚本的改进项**:`--only` 模式应写 `REPORT3_s2.md` 而不是覆盖主报告。


---

## 14 · 第六/七轮:双通道实测(`m0_dualchannel.py`)**第四批 E1 证据**

### 14.1 🔴 第六轮那次全量跑是废的 —— 我的 bug,不是模型的问题

2026-10-18 的 `out4/REPORT4.md`,第 2~4 轮正文字数均为 0、`finish_reason=None`、
耗时 390~430ms(远短于正常的 7s)。看报告像是「模型拒绝输出」,实际是**请求根本没被接受**。

原始证据 `out4/openrouter/s_t2_narr_raw.txt`(139 字节,三轮完全一致):

```json
{"error":{"message":"messages.5: Invalid input: expected object, received string","code":400},"user_id":"user_3Kl..."}
```

**根因(我的代码错误)**:`build_narrative_messages()` 的文档注释说 `history` 的元素是
「messages 片段(list)」,于是写了 `for group in history: msgs.extend(group)`;
但调用方用的是 `history.extend(group)` —— `history` 其实是**扁平的 dict 列表**。
于是 `msgs.extend(dict)` 退化成「遍历字典的键」,把 `"role"` / `"content"` 两个**字符串**塞进了 messages。

两个让它躲过检查的巧合:
1. **第 1 轮 `history` 为空**,循环体不执行 → 第 1 轮永远正常;
2. **`--dry-run` 当时只装配第 1 轮 + 空 history** → 等于专门测了唯一不会出错的那条路径。

佐证:报告里 messages 条数是 `9 → 17 → 25 → 32`(每轮 +8),
而正确值应是 `9 → 13 → 17 → 20`(每轮 +4:玩家输入 / 世界书 / 上轮变化 / 上轮 assistant)。
**+8 正好是 +4 个 dict 每个被拆成 2 个键名。** 修复后已复现为 `9 → 13 → 17 → 20`。

### 14.2 ⭐ 可直接用于产品的 E1 结论:OpenRouter 的 400 错误体结构

这次事故意外拿到了一份**非流式错误体**的真实样本,对 `C-11`(错误提示人话化)有用:

| 观察 | 值 | 对客户端的含义 |
|---|---|---|
| HTTP 状态 | `400` | — |
| 错误体 | `{"error":{"message":..., "code":400}, "user_id":...}` | **`code` 在 `error` 对象里**,与 OpenAI 的 `error.type`/`error.param` 结构**不同**,不能照搬 OpenAI 的解析 |
| `message` 格式 | `messages.5: Invalid input: expected object, received string` | **带 JSON 路径前缀**(`messages.5`),定位能力很强 |
| 响应体大小 | 139 字节 | 极小 |
| 返回速度 | **390~430ms** | 远快于正常生成(首字节 2.7s) |
| 是否计费 | ❌ 不计费(未进入生成) | 与 E1 硬约束 ⑩「中断不省钱」相反 —— **400 是真不花钱** |
| 顶层 `user_id` | 有 | 可用于向 OpenRouter 报障时定位 |

> 📌 **给客户端的要求**:
> ① 400 的 `error.message` **直接展示给用户**(它比我们自己编的提示准确得多),但要**剥掉 `user_id`** 再写日志(铁律 4);
> ② **非 2xx 必须单独成一条错误态**,绝不能和「模型返回了空内容」混为一谈 —— 本次报告正是栽在这里;
> ③ 流式请求失败时,错误体是**一次性的 JSON**,不是 SSE —— 解析器必须先看 HTTP 状态码,再决定按 SSE 还是按 JSON 读。

### 14.3 🔴 报告本身的缺陷(比 bug 更值得记)

原报告把 3 次失败调用算进了分母,给出:

| 原报告写的 | 真相 |
|---|---|
| `D1 正文硬污染率 0% (0/4)` ✅ 通过 | 只有 **1 个有效样本**,其余 3 个是 HTTP 400 |
| 失败轮次的「硬污染 / 软污染」列 | 显示 **`✅ 无`** —— 没有正文当然没有污染,但这读起来像是通过了 |

**一份"看起来全绿"的报告,比一份明确报错的报告危险得多。**

已修复:失败样本从分母剔除 / 顶部挂 🔴 红旗并列出每轮错误 / 判定降级为「⚠️ 样本不足(n/N),不作数」/
明细表里失败轮显示「— 调用失败」而非「✅ 无」。教训入 `AGENTS.md §8`。

### 14.4 本轮变更

| 项 | 状态 |
|---|---|
| `V-12` | 🔴 **仍未关闭** —— 第六轮数据作废,等第七轮重跑 |
| `ADR-011` | ✅ 2026-10-19 用户拍板确认 |
| `C-11` | 补充 OpenRouter 400 错误体结构要求(见 §14.2) |
| `AGENTS.md §8` | +4 条工具坑 |
