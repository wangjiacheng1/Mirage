# tools · M0 阶段的验证脚本

> 这些脚本**只在 M0 验证阶段使用**,不是产品代码,不会进 Android 工程。
> 目的是在写任何 Kotlin 之前,把"服务商到底怎么回事"搞清楚。

## 🔴 使用前的安全须知

| 规则 | 说明 |
|---|---|
| Key 只走环境变量 | 脚本**不读配置文件**,也不接受命令行传 Key |
| 不要改脚本把 Key 写死 | 哪怕"临时测一下"。2026-10-16 已经因此泄露过一次 |
| 产出物已自动脱敏 | 但发出去之前仍建议手动确认一遍(见下) |

```bash
# 发给我之前,先自查一遍(应该没有任何输出)
grep -rn 'sk-' out/ out2/ out3/ | grep -v REDACTED
```

---

## m0_probe.py · 协议探针

补齐 `docs/14 §7` 列出的协议缺口。**只用标准库,不需要 pip install。**

### 用法

```bash
# 1. 设置 Key(用吊销重建后的新 Key)
export MIRAGE_DS_KEY="sk-..."           # DeepSeek
export MIRAGE_OR_KEY="sk-or-v1-..."     # OpenRouter

# 2. 可选:指定模型(有默认值)
export MIRAGE_DS_MODEL="deepseek-chat"
export MIRAGE_OR_MODEL="z-ai/glm-5.1"

# 3. 跑
python3 tools/m0_probe.py               # 两家都跑
python3 tools/m0_probe.py deepseek      # 只跑一家
```

### 它测什么

| 探测项 | 对应缺口 | 为什么要测 |
|---|---|---|
| 非流式连通性 + usage | — | 基线 |
| 错误 Key 的响应体 | **V-11** | `C-10` 错误提示文案要照着它写 |
| ⭐ 带毫秒时间戳的**完整原始 SSE 流** | **V-9 / V-14 / V-15** | **Android SSE 解析器的实现依据**。重点是「结束标志到底是 `data: [DONE]` 还是直接断流」 |
| 读 5 个 chunk 后强行断开 | **V-13** | `C-2` 中断生成 |
| 思维链 开 vs 关 的 usage 对比 | **V-8** | 验证"关思考"是真省钱,还是只是"不返回但照样计费" |
| `max_tokens=256` + 开思考 | **R-T15** | 复现"思考吃光预算导致 content 为空" |
| `GET /models` | **V-10** | 验证 `A-4`(贴完 Key 自动拉模型列表)能不能做 |

### 产出

```
out/
├── REPORT.md                 ← ⭐ 汇总报告,直接发我这一份就行
├── deepseek/
│   ├── 01_nonstream.json
│   ├── 02_badkey.json
│   ├── 03_stream_raw.txt     ← 最有价值的原始证据
│   ├── 04_abort.txt
│   ├── 05_reasoning_on.json / 05_reasoning_off.json
│   ├── 06_starved.json
│   └── 07_models.json
└── openrouter/ (同上)
```

### 成本

约 10 次请求 / 家,输出都在几百 token 量级。**总花费约 ¥0.1 以内。**

### ⚠️ 脚本测不出来、需要你手动看一眼的

| 项 | 怎么做 |
|---|---|
| **V-13 中断是否继续计费** | 跑完后去平台的 **用量 / Activity 页面**,找到被中断那次请求,看它记了多少 completion token。如果接近完整回复 → 中断不省钱 |
| **V-12 `stream` + `tools`** | 这个要结合角色卡语义设计,见 `docs/12 §4.7` Step 4。**这是 M0 的核心闸门** |

---

### 已修复的 bug

| 日期 | 症状 | 根因 | 教训 |
|---|---|---|---|
| 2026-10-16 | `m0_probe2.py` 的 **`V-13b` 结果全是 `None`**(计费 token / cost / cancelled 都查不到) | `GET /api/v1/generation?id=<gen_id>` 返回 **HTTP 404「Generation not found」**。gen_id 提取正确、`Authorization` 头也正确 → 是**落账延迟超出了我的轮询窗口**(只轮询 6 次 × 4s ≈ 20s) | 🔴 **凡是「写入后读」的接口,轮询窗口不能凭感觉拍。** 正确做法:先拿一个**旧 ID** 做零成本探测,定出落账延迟的量级,再决定窗口。已写成 `m0_probe3.py` 的 S1 步。**连带产品结论:成本数字不能做成「请求一结束就显示」**(`docs/13 §12.5`) |
| 2026-10-16 | `m0_probe2.py` 跑到 Key 校验就崩:`AttributeError: 'tuple' object has no attribute 'strip'`,且**报告没落盘** | 我在 `main()` 里先调了 `sanitize_key(raw)`(返回元组 `(key, notes)`),又把元组传给 `validate_key()` —— 而后者**内部已经调过 `sanitize_key`** 了 | ① 复用别处的函数前先确认返回签名;② **报告写入必须放 `finally`** —— 已收集到的数据不能因为后面一步崩了就全丢。现在每个探测项都包了 `try`,单项失败只会在报告顶部列一行 |

---

## 🔧 排错

### 全部探测返回 `HTTP -1`,`out/` 里是 0 字节文件

**2026-10-16 首跑就是这个**(`docs/14 §9`)。看 `01_nonstream.json`:

```
EXCEPTION: UnicodeEncodeError('latin-1', 'Bearer “sk-...', 7, 8, 'ordinal not in range(256)')
                                                  ↑ 全角引号 U+201C
```

**原因:`export MIRAGE_DS_KEY=“sk-...”` 用了中文输入法的智能引号 `“ ”`,不是 ASCII 的 `"`。**
shell 不认它是引号,于是它变成了 Key 的一部分;而 HTTP 头只能承载 latin-1,请求压根发不出去。

**现在脚本会在预检阶段自动处理**(引号/空白/零宽字符自动剥掉,修不了的明确报出是第几个字符)。
如果仍想手动确认:

```bash
python3 -c "import os;k=os.environ['MIRAGE_DS_KEY'];print(repr(k[:8]), len(k))"
# 正常:  'sk-cb382' 35
# 有问题:'\u201csk-cb38' 37   ← 开头是 \u201c 就是智能引号
```

> 💡 **省事的写法:`export` 时干脆不加引号。** API Key 不含空格,不需要引号包裹:
> ```bash
> export MIRAGE_DS_KEY=sk-xxxxxxxx
> ```

### 预检通过,但第一步就报 401

Key 本身格式没问题但无效。检查:① 是不是吊销后忘了换成新 Key;② 平台后台是否还有余额。

### 预检通过,第一步报 404 / `Model Not Exist`

模型 ID 不对。用环境变量改:

```bash
export MIRAGE_DS_MODEL="deepseek-chat"      # 或 deepseek-reasoner / deepseek-flash
export MIRAGE_OR_MODEL="z-ai/glm-5.1"
```

### 某一家第一步失败后,报告里那家全是空

**这是故意的。** 第一步不通就跳过剩余 6 项,避免 14 次请求撞同一面墙、把报告塞满 `None`。
失败原因会单独列在报告顶部。

---

## m0_probe2.py · 协议探针 #2 ✅ **已跑通,不用再跑**

> 2026-10-16 20:24 跑通,结论见 `docs/14 §12`。**关闭了 `V-12a/16/17/18/19/20`,解除了 `V-12` 的技术阻塞。**
> ⚠️ 其中 **`V-13b` 因我的 bug 无效**(见上面「已修复的 bug」),改由 `m0_probe3.py` 重测。

### 它测什么

| 项 | 问题 | 为什么现在就得测 |
|---|---|---|
| **V-17** 🔴 | OpenRouter 的 `provider` 锁定到底生效不生效 | **这是 `V-12`(M0 核心闸门)的前置。** 实测 60 秒内同一 model id 命中 4 个不同上游(`docs/14 §10.4`)—— 不锁 provider,Step 4 的数据就是废的 |
| **V-12a** 🔴 | `stream` + `tools` 组合压根能不能用(DeepSeek 单一上游 + OR 锁定上游) | 这是方案 A 的生死线。重点看 **arguments 是否被分片、拼接后能否解析成合法 JSON** |
| **V-12b** | 从**已下载**的 `07_models.json` 预筛支持 tools 的模型 | **零成本,不发任何请求**。跑 `--no-net` 就能出结果 |
| **V-16** | DeepSeek 不传思考参数时默认思不思考 | `/models` 声明 `effort.default_level="high"` 但实测没返回 `reasoning_content`,矛盾待解 |
| **V-18** | DeepSeek 流式下思考字段叫什么 | 第一轮流式没开思考,没取到样本;归一化层缺这一格 |
| **V-19** | `effort` 三档(low/high/max)的 token 差异 | ~~若有效 → 能做成"思考深度"三档 UI~~ → ❌ **实测无效**(90/64/76,无递增),**建议已撤回** |
| **V-13b** 🆕 | **中断后到底扣不扣钱** | 平台用量页只能按模型/日期聚合、查不到单笔 → 改用 OpenRouter 的 `GET /api/v1/generation?id=<gen_id>`**精确查单笔计费**。同一 prompt 跑两次(一次跑完、一次读 5 行就断),直接对比 completion token 与 cost |
| **V-20** 🆕 | DeepSeek `/user/balance` 余额端点 | 顺带验证能否做「剩余额度」展示 —— BYOK 用户最关心的就是还剩多少钱 |

### 用法

```bash
# 零成本先看一眼(不发请求,只分析本地已下载的模型列表)
python3 tools/m0_probe2.py --no-net

# 完整跑(约 15 次请求)
export MIRAGE_DS_KEY="sk-..."
export MIRAGE_OR_KEY="sk-or-v1-..."
export MIRAGE_DS_MODEL="deepseek-flash"     # 可选,与上一轮保持一致便于对照
export MIRAGE_OR_MODEL="z-ai/glm-5.1"       # 可选
python3 tools/m0_probe2.py
```

**产出:`out2/REPORT2.md`(直接发我)+ `out2/<provider>/*` 原始文件。**

### 成本

约 15 次请求,prompt 全部极短且**全程显式关思考**(`docs/14 §10.3`:不传思考参数贵 7.6 倍)。
按上一轮的单价推算 **< $0.02**。唯一偏贵的是 V-12a 的工具调用(需要真实输出),仍在分币级别。

### ⚠️ 脚本测不出来、需要你手动看一眼的

| 项 | 怎么看 |
|---|---|
| 消费上限 | **OpenRouter → Settings → Credits** 给当前 Key 设个 **credit limit**(建议 $5)。这比换 Key 更省事,且损失封顶 |

> ✅ **`V-13b` 不用你手动查了。** 原先让你去 Activity 页找单笔请求,
> 但用户反馈那里**只能按模型/日期聚合**。已改为脚本内用 OpenRouter 的
> `GET /api/v1/generation?id=` 精确查单笔 → 现在是 `m0_probe2.py` 的自动项。

---

## m0_probe3.py · 协议探针 #3 ⭐ **现在该跑这个(专修 V-13b)**

> 小而专,只干三件事。**前两步零成本**,第三步约 $0.0001。
> **这项不阻塞任何事**,只影响「停止生成」按钮的 UI 文案 —— 有空再跑。

### 设计思路:先诊断,再测量

| 步 | 做什么 | 成本 | 为什么这么设计 |
|---|---|---|---|
| **S1** | 拿 `out2/` 里那两个**几小时前**的 `gen_id` 重查一次 | **0** | **二选一地定死根因**:现在能查到 → 是落账延迟,窗口拉长即可;现在还 404 → 是端点/权限问题,generation 方案作废 |
| **S2** | `GET /api/v1/credits` + `GET /api/v1/key` | **0** | 关闭 `V-21`(`A-17` 剩余额度展示的依据);更重要的是 `total_usage` 是高精度浮点,**可以当独立的测量工具**,不再单靠 generation 端点 |
| **S3** | credits 差值 + generation 长轮询(90s)**双口径**测「中断 vs 完整」 | ~$0.0001 | 两套证据**互相校验**。上一轮栽在只有一套证据上 |

### 用法

```bash
export MIRAGE_OR_KEY="sk-or-v1-..."     # 本支只需要 OpenRouter

python3 tools/m0_probe3.py                # 全跑(约 3~6 分钟,大部分时间在等落账)
python3 tools/m0_probe3.py --only s1,s2   # 只跑零成本部分,一分钱不花
python3 tools/m0_probe3.py --only s1      # 只要根因诊断
python3 tools/m0_probe3.py --settle 120   # 落账等待窗口改成 120 秒
```

**产出:`out3/REPORT3.md`(直接发我)+ `out3/openrouter/*` 原始文件。**

> 💡 **如果时间紧,只跑 `--only s1,s2`。** 这两步零成本,
> 而且已经能回答「额度展示能不能做」和「404 到底是什么原因」两个问题。
> S3 只是为了把「停止生成是否省钱」这一句 UI 文案敲实。

### 本支脚本相对前两支的改进

| 改进 | 来自哪次踩坑 |
|---|---|
| `--only <step>` 支持单项重跑 | 上一轮为了重测一项要全跑一遍 |
| `provider` **从流内取**,不再依赖 generation 查询 | 上一轮 generation 查不到,连已经拿到手的 provider 也一起丢了 |
| 轮询会**记录每次尝试的 HTTP 码和首次成功耗时** | 这样「落账延迟」本身变成可量化的数据,而不是一句"查不到" |
| 双口径 + 自动交叉校验 | 单一证据源一旦失效,整项就废了 |

---

## m0_fixture.py · 双通道测试的场景材料(不单独运行)

被 `m0_dualchannel.py` import。**全部内容取自用户 2026-10-18 交付的真实角色卡 `DiLaoJSON.json`**
(`spec: chara_card_v2`),所以我们测的是真实场景,不是我编的玩具。

| 常量 | 来自卡的哪个字段 | 用途 |
|---|---|---|
| `CARD_SYSTEM` | `data.system_prompt` | 稳定前缀 |
| `CARD_PERSONA` | 人物卡正文 | 稳定前缀 |
| `FIRST_MES` | `data.first_mes`,**但剥掉了 `<initvar>{...}</initvar>` 与 `<mvu-status/>`** | 开场白 |
| `POST_HISTORY` | `data.post_history_instructions` | 队尾指令帧第 ③ 段 |
| `WRITING_RULES` | 行文规范 | 稳定前缀 + 队尾帧第 ① 段 |
| `MVU_BAN` | 对应 `docs/15 §3.1` 的 `mvu-background-owner` 禁令 | 队尾帧第 ④ 段 |
| `SETTLE_RULES_STRICT` | `character_book.entries[10]`(`[mvu_update]` 状态更新规则,`constant=true`) | 结算 system |
| `SETTLE_RULES_LOOSE` | 上面那份**删掉「正文优先」与「不得凭空编造」两条**后的对照组 | D5 |
| `INIT_STATE` | `character_book.entries[11]`(`[initvar]` 状态初值) | 28 个变量路径 |
| `VAR_CATALOG` | 由 `INIT_STATE` 推导(只有路径 + 类型,**没有值**) | `docs/05 §1.2` |
| `WORLDBOOK` | `entries[2/5/7]` 节选(难度 / 战斗 / 水壶) | 条件注入 |
| `SCRIPT` | 4 轮固定玩家输入 + **33 条断言** | D6 |

> 🔴 **为什么必须剥掉 `first_mes` 里的 `<initvar>`:**
> 真 SillyTavern 把一整块 `<initvar>` JSON 直接内嵌在开场白里,而开场白会作为 `assistant` 消息进入历史
> —— 等于给模型做了一次「**你应该在正文里输出状态块**」的 few-shot 示范。
> 我们走方案 C(正文零数值),不剥掉的话 D1 测的就是角色卡自带的污染源,不是我们的装配。

### 4 轮脚本的设计意图

| 轮 | 玩家输入 | 测什么 |
|---|---|---|
| 1 | "我选简单。" | 最简单的任务:难度与总轮数写入 |
| 2 | 厮杀、受伤、杀光、捡到金币 | **多字段联动 + 方向断言**(生命必须减、经验必须加、金币必须加) |
| 3 | 喝一口水 | **带计算式**(生命 + 每口恢复),且水壶必须**减**而不是加 |
| 4 | 🔴 "我没有动。我站在原地,什么也没做。" | **负向测试 —— 本脚本最重要的一轮。**`delta` 应该是**空的**。DZMM 实测的「只增不减 / 无中生有」就是在这种轮次暴露的(`docs/11 §8.2`) |

---

## m0_dualchannel.py · 双通道实测 ⭐ **现在该跑这个(关 `V-12`,M0 最后一道核心闸门)**

验证 `docs/05 §4.0` 定的**方案 C**:「叙事调用」与「结算调用」两次完全独立的请求,
正文只写字(零数值),结算只吐 JSON delta。

> 🔴 **为什么必须测:**方案 C 在 `docs/05 §4.4` 被我自己标成了 **E3** ——
> "行业做法是 C"这一点是 E1(`dsh-tavern` + 真 SillyTavern 双来源互证),
> 但**结算请求长什么样**没有任何竞品证据,因为导出的 JSON 里只有前台帧。
> 这支脚本就是把那段 E3 变成 E1 或推翻它。

### 六个测试项

| 项 | 测什么 | 及格线 |
|---|---|---|
| **D1** | **正文纯净度**(叙事调用:流式 · **不传 `max_tokens`** · 关思考)。检查正文里有没有 JSON / `<initvar>` 标签 / Markdown 表格 / JSON Pointer 赋值(**硬污染**),以及字段名+数字 / `48/60` 分数 / 【状态】小标题(**软污染**) | **硬污染率 0%** |
| **D2** | **结算可解析率 + schema 合法率**(非流式 · `max_tokens=512` · `temperature=0.2` · 关思考)。额外单独统计「**不需容错即可直接解析**」的比例 —— 需要剥围栏才行,说明 Prompt 还得改 | **≥ 95%** |
| **D6** | **断言驱动的结算质量**:`must_change`(该变的变了)/ `must_not_change`(不该变的没动)/ `direction`(方向对不对),共 33 条 | 参考值,定 Prompt 迭代方向 |
| **D3** | **廉价模型够不够用**:同一批正文换个更便宜的模型做结算 | 对照 |
| **D5** | **约束值多少钱**:`STRICT` vs `LOOSE`(删掉「正文优先」+「不得凭空编造」两条) | 对照 |
| **D7** | 🆕 **`delta` 语义对照**:写「绝对新值」 vs 写「增量」,哪种模型更不容易搞错 | 对照 |
| **D4** | **成本对照**:两次调用的真实 token 与花费,以及结算占比 | 记录 |

> **D7 的起因是我自己文档里的一处矛盾。** `docs/05 §4.4` 的示例曾写成
> `{"/冒险者/生命": -12, "/物品栏/材料": ["锈铁片"]}` —— 前者是增量、后者是新值,**同一个对象混了两种语义**。
> 已在 `docs/05 §4.4.2` 改正为**一律用绝对新值**并给了 4 条理由,D7 就是来验证这个选择的。

### 用法

> 🔴 **2026-10-19 修复记录(必读)**
>
> 第六轮那次全量跑产出的 `REPORT4.md` 是**废报告**:第 2~4 轮全部 HTTP 400
> (`messages.5: Invalid input: expected object, received string`),根因是
> `build_narrative_messages()` 把扁平的 `history` 又当「片段列表」二次展开,
> 导致 `msgs.extend(dict)` 退化成遍历字典键,把 `"role"`/`"content"` 当成了消息。
> 第 1 轮 `history` 为空所以不触发,而当时的 `--dry-run` **只装配第 1 轮**,等于没测。
>
> 已修三处 + 加一道守卫:
> 1. `msgs.extend(history)`(不再二次展开);
> 2. 新增 `assert_messages_wellformed()`,**叙事/结算发送前都过一遍**,结构不合法就地抛异常;
> 3. `--dry-run` 改为**走完整个脚本**,逐轮过守卫并打印 messages 增长曲线;
> 4. 空正文不再被当有效缓存复用;
> 5. 报告侧:失败样本从比率分母剔除、顶部挂红旗、判定降级为「样本不足,不作数」。
>
> **重跑前先 `rm -rf out4`**,清掉上次失败留下的空正文缓存。

```bash
rm -rf out4                                   # 清掉上次失败的残骸(重要)
export MIRAGE_OR_KEY="sk-or-v1-..."          # 或 MIRAGE_DS_KEY
export MIRAGE_OR_MODEL="z-ai/glm-5.1"        # 可选,有默认值

# 0. 先零成本看一眼装配对不对(不发任何请求)
#    现在会逐轮自检,正常应看到 messages 条数 9 -> 13 -> 17 -> 20(每轮 +4)
python3 tools/m0_dualchannel.py --dry-run

# 1. 全跑(默认 openrouter,4 轮,约 20 次调用)
python3 tools/m0_dualchannel.py

# 其它
python3 tools/m0_dualchannel.py --provider deepseek
python3 tools/m0_dualchannel.py --rounds 2                 # 只跑前 2 轮,省钱
python3 tools/m0_dualchannel.py --only s                   # 只跑主会话(叙事+结算)
python3 tools/m0_dualchannel.py --only d5,d7               # 复用缓存正文,只跑对照组
python3 tools/m0_dualchannel.py --settle-model deepseek/deepseek-chat   # D3 的廉价模型
python3 tools/m0_dualchannel.py --no-reuse                 # 强制重新生成正文
```

### ⭐ 正文缓存:对照组不用重复付叙事的钱

主会话跑完后,4 段正文 + 每轮的输入快照会写进 `out4/narratives.json`。
之后跑 `--only d3,d5,d7` 时会**自动复用**,只花结算调用的钱(每次 ≤512 token)。

这也保证了**对照组之间输入完全一致** —— 三组用的是同一批正文 + 同一批 `snapshot_in`,
每组只改一个变量,所以差异可以归因。

### 成本

| 项 | 次数 | 说明 |
|---|---|---|
| 叙事调用 | 4(只在主会话) | **不设 `max_tokens`**,约 400 字正文 |
| 结算调用 | 16(S/D3/D5/D7 各 4) | 每次 ≤ 512 token |
| 合计 | 约 20 次 | glm-5.1 / deepseek-flash 这档约 **$0.01 ~ $0.03** |

脚本启动会打印预估并给 **3 秒反悔窗口**(Ctrl-C 可取消)。你的 OpenRouter credit limit 已设为 $5,跑飞也烧不穿。

### 产出

| 文件 | 内容 |
|---|---|
| `out4/REPORT4.md` | ⭐ **这份直接发我** |
| `out4/narratives.json` | 叙事正文缓存(贵,别删) |
| `out4/<provider>/s_t*_narr_raw.txt` | 叙事调用原始 SSE(带毫秒戳) |
| `out4/<provider>/s_t*_narr_text.txt` | 拼好的正文 |
| `out4/<provider>/*_settle_raw.json` | 每次结算调用的原始响应 |
| `out4/raw_report4.json` | 全部原始数据,含每轮完整状态快照 |
| `out4/dryrun_messages.json` | `--dry-run` 时的完整装配 |

### ⚠️ 脚本测不出来、需要你手动看一眼的

| 项 | 怎么看 |
|---|---|
| **正文好不好看** | 脚本只能判断"有没有数值污染",**判断不了叙事质量**。请读一遍 `out4/REPORT4.md` 第 1 节折叠里的 4 段正文,凭感觉打个分:人设像不像?有没有替你做决定?有没有出戏? |
| **`posture` 有没有用** | 结算器会吐一段 ≤100 字的"现场姿态"。看它是否真的能当下一轮的上下文用,还是只是把正文复述一遍 |
| **`reason` 能不能当溯源** | 这是 `E-5b`「点数值看为什么变」的数据来源。看它是否具体到"第几句话造成的" |
| **第 4 轮** | **重点看这一轮。**玩家什么都没做,`delta` 应该是空的。如果模型凭空加了金币或推进了轮次,就是复制了 DZMM 的缺陷 |

### 本支脚本做的工程防护(前几轮踩坑换来的)

| 防护 | 来自哪次踩坑 |
|---|---|
| `--only` 模式写 `REPORT4_<项>.md`,**不覆盖主报告** | 上一支脚本犯过这个错(`docs/14 §13.7`) |
| 报告写入放 `finally`,每项探测包 `try` | 第一轮一项崩掉丢了整份报告 |
| `validate_key` 返回 `(key, msgs)`,**不要再手动调 `sanitize_key`** | 上一轮在这里崩过一次(`'tuple' object has no attribute 'strip'`) |
| 所有产出物过 `redact()` | 铁律 4 |
| 只用标准库 | 沙箱无网络,装不了包 |
| 50+ 项离线自检 + 全链路 mock 模拟 | 这次自检**真的抓到了一个 bug**(报告模板里 `| 0% |` 的 `%` 没转义,会让整份报告渲染失败) |

---

## 待写(说一声就写)

| 脚本 | 作用 | 对应 |
|---|---|---|
| ~~`m0_dualchannel.py`~~ | ✅ **已完成**(见上)。注意:**实际实现的是方案 C,不是原计划的 A/B 对比** —— `docs/15` 的 E1 证据让 A/B 对比变得没必要了(A 只解析不生成、B 不可依赖) | `docs/12 §4.7` Step 4 |
| `m0_eval.py` | 15 个"刁难场景"批量跑,输出 Markdown 待打分 | `docs/12 §4.8` Step 5 |

> ✅ **`m0_dualchannel.py` 的阻塞已全部解除并已完成。** 留档一下当初的三条硬约束,
> 它们都已写进脚本:
> - **超时** —— 叙事 180s / 结算 120s(锁定 provider 时实测出现 59.7s 单次耗时,`R-T24`);
> - **`arguments` 按 `index` 归集 + 只在 `[DONE]` 后解析** —— 方案 C 不走 tools,这条暂时用不上,但 `docs/02 §5.1.1c` 的结论保留;
> - **测试集覆盖便宜档** —— 由 `--settle-model`(D3)实现,现成清单在 `out2/openrouter/v12b_tools_capable_models.json`。
>
> 🔴 **原计划的「方案 A / B 对比」已作废,这是一次有依据的范围收缩:**
> `docs/15` 的 E1 证据显示行业做法是 C,A 只需"能解析"不需"能生成",B 在用户随便填的通道上不可依赖。
> 花钱做 A/B 横评不会改变结论,所以不做。
