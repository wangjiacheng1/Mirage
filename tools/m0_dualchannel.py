#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Mirage · M0 双通道实测 (m0_dualchannel.py) —— 关闭 V-12,定死状态协议

这是 M0 的**最后一道核心闸门**。前三支探针解决的是「协议能不能用」(SSE/思考预算/
计费/中断),这一支解决的是「我们自己设计的那套东西能不能用」:

    docs/05 §4.0 定的方案 C =「叙事调用」与「结算调用」两次完全独立的请求。
    叙事只写正文(零数值),结算只吐 JSON delta。

方案 C 在 docs/05 §4.4 里被我自己标成了 **E3(没有竞品证据,是我们自己的设计)**,
因为 dsh-tavern 导出的 JSON 里只有前台帧,后台结算请求拿不到。
所以它必须靠实测过关 —— 这支脚本就是那次实测。

测试材料全部来自用户交付的真实角色卡 DiLaoJSON.json(见 tools/m0_fixture.py),
不是我编的玩具场景。

------------------------------------------------------------------------------
六个测试项
------------------------------------------------------------------------------
  S  (= D1+D2+D6)  主会话:4 轮「叙事 -> 结算 -> 应用 delta -> 下一轮」
       D1 正文纯净度:流式、不设 max_tokens、关思考 -> 正文里有没有 JSON/状态块/数值表格
                     及格线:硬污染率 0%
       D2 结算可解析率 + schema 合法率   及格线:>= 95%
       D6 断言驱动的结算质量:must_change / must_not_change / direction 三类断言
  D3  廉价模型够不够用:同一批正文换一个更便宜的模型做结算,比可解析率与断言通过率
  D5  约束有效性对照:SETTLE_RULES_STRICT vs SETTLE_RULES_LOOSE
       (测 docs/05 §4.4.1 那四条抄自真 ST 的约束到底值多少钱)
  D7  🆕 语义对照:delta 写【绝对新值】 vs 写【增量】,哪种模型更不容易搞错
       (起因:docs/05 §4.4 的示例自己就是矛盾的,见下方「已知文档缺陷」)
  D4  成本对照:方案 C 两次调用的真实 token 与花费

------------------------------------------------------------------------------
🔴 已知文档缺陷(本脚本要用数据来定死它)
------------------------------------------------------------------------------
docs/05 §4.4 的输出示例是:
    {"delta": {"/冒险者/生命": -12, "/物品栏/材料": ["锈铁片"]}}
这里 `-12` 是**增量**,而 `["锈铁片"]` 是**新值** —— 同一个对象里混了两种语义,
是我写文档时的疏忽。而 docs/15 §1.3 实测的真 ST 回灌格式 `/冒险者/生命 = 58`
用的是**绝对新值**。

倾向结论(待 D7 验证):**用绝对新值**。理由:
  1. 幂等 —— 同一个 delta 重放两次结果一样,对 A-18 截断重放是刚需
  2. 不累积漂移 —— DZMM 实测的「只增不减」本质就是增量累加失配
  3. 模型不用做算术 —— 它只需要读正文 + 读快照,把新值抄出来
  4. 与真 ST 的回灌格式一致 -> 导入导出互通更省事
D7 就是来验证「模型是不是真的更不容易搞错绝对值」。

------------------------------------------------------------------------------
成本与用法
------------------------------------------------------------------------------
一次全跑 ≈ 4 次叙事(不限 max_tokens,约 400 字) + 16 次结算(各 <= 512 token)
          ≈ 20 次调用,glm-5.1 / deepseek-flash 这档价位下约 $0.01~0.03。
脚本启动时会打印预估并给 3 秒反悔窗口。

    export MIRAGE_OR_KEY="sk-or-v1-..."        # 或 MIRAGE_DS_KEY
    python3 tools/m0_dualchannel.py                      # 全跑(默认 openrouter)
    python3 tools/m0_dualchannel.py --provider deepseek
    python3 tools/m0_dualchannel.py --only s              # 只跑主会话
    python3 tools/m0_dualchannel.py --only d5,d7          # 复用已缓存正文,只跑对照组
    python3 tools/m0_dualchannel.py --rounds 2            # 只跑前 2 轮(省钱)
    python3 tools/m0_dualchannel.py --settle-model deepseek/deepseek-chat   # D3 的廉价模型
    python3 tools/m0_dualchannel.py --dry-run             # 只打印装配出来的 prompt,不发请求

产出:
    out4/narratives.json      <- 叙事正文缓存(贵,复用它跑对照组就不用再花钱)
    out4/<provider>/*.txt     <- 每次调用的原始响应
    out4/REPORT4.md           <- 这份直接发我
    out4/raw_report4.json

⚠️ --only 模式写 out4/REPORT4_<项>.md,**不覆盖主报告**(docs/14 §13.7 记过的坑)
"""

import json
import os
import re
import sys
import time
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from m0_probe import (  # noqa: E402
    PROVIDERS,
    redact,
    validate_key,
    post_json,
    post_sse,
    extract_usage,
    summarize_usage,
)
from m0_probe2 import say, jget, run_step  # noqa: E402
import m0_fixture as FX  # noqa: E402

OUT_DIR = os.path.join(HERE, "..", "out4")
NARR_CACHE = os.path.join(OUT_DIR, "narratives.json")

# 结算调用参数(docs/05 §4.4 定稿)
SETTLE_MAX_TOKENS = 512
SETTLE_TEMPERATURE = 0.2
# 叙事调用:**不传 max_tokens**(E1 硬约束 1 —— 它是「思考+正文」共用预算)
NARRATIVE_TIMEOUT = 180
SETTLE_TIMEOUT = 120


def write_out(provider, name, content):
    d = os.path.join(OUT_DIR, provider)
    os.makedirs(d, exist_ok=True)
    path = os.path.join(d, name)
    with open(path, "w", encoding="utf-8") as f:
        f.write(redact(content if isinstance(content, str) else str(content)))
    return path


# ==========================================================================
# 1. 状态工具(JSON Pointer 寻址)
# ==========================================================================
def ptr_get(state, path):
    cur = state
    for seg in path.strip("/").split("/"):
        if not isinstance(cur, dict) or seg not in cur:
            return None, False
        cur = cur[seg]
    return cur, True


def ptr_set(state, path, value):
    segs = path.strip("/").split("/")
    cur = state
    for seg in segs[:-1]:
        cur = cur.setdefault(seg, {})
    cur[segs[-1]] = value


def deep_copy(obj):
    return json.loads(json.dumps(obj, ensure_ascii=False))


def snapshot_lines(state):
    """全量权威状态,按 docs/15 §1.3 实测格式:JSON Pointer = JSON 字面量,一行一条。"""
    flat = FX.flatten(state)
    return "\n".join("%s = %s" % (p, json.dumps(v, ensure_ascii=False))
                     for p, v in flat.items())


def delta_lines(delta):
    if not delta:
        return "(上一轮没有任何变量发生变化)"
    return "\n".join("%s = %s" % (p, json.dumps(v, ensure_ascii=False))
                     for p, v in delta.items())


# ==========================================================================
# 2. 叙事调用的装配(严格按 docs/05 §1 v1.1)
# ==========================================================================
VAR_CATALOG_HINT = """【变量路径与类型索引】
以下是初始化后的一次性变量路径与类型索引,**不代表当前值,也可能未列全**。数组仅列容器。
%s"""


def build_stable_prefix():
    """messages[0..4] —— 整局一字不改,吃满 prompt 前缀缓存(docs/14 §2.2)。"""
    system = "\n\n".join([
        FX.WRITING_RULES,
        FX.CARD_SYSTEM,
        FX.CARD_PERSONA,
        VAR_CATALOG_HINT % json.dumps(FX.VAR_CATALOG, ensure_ascii=False),
    ])
    return [
        {"role": "system", "content": system},
        {"role": "user", "content":
            "我们正在共同创作一部持续发展的沉浸式虚构小说。你负责叙述与扮演除我之外的全部角色。"},
        {"role": "assistant", "content":
            "明白。后续我会把人物卡与世界设定当作事实依据,只输出小说正文,不输出任何元信息。"},
        {"role": "user", "content": "开始。请从角色卡给定的开场继续。"},
        {"role": "assistant", "content": FX.FIRST_MES},
    ]


def build_narrative_messages(history, player_input, worldbook_key, last_delta):
    """
    history: 已发生轮次的**扁平** message dict 列表(调用方用 extend 累积,不是嵌套 list)。
    返回完整 messages。队尾指令帧永远是最后一条 user。

    🔴 2026-10-18 修复(bug #1):这里原来写的是 `for group in history: msgs.extend(group)`,
    而调用方(第 9 节)用的是 `history.extend(group)` —— history 其实是扁平的 dict 列表。
    于是 `msgs.extend(dict)` 退化成「遍历 dict 的键」,往 messages 里塞进了
    "role" / "content" 两个**字符串**,导致第 2 轮起 HTTP 400:
    `messages.5: Invalid input: expected object, received string`。
    第 1 轮 history 为空,所以恰好不触发 —— 也正因如此 --dry-run 没能发现它。
    """
    msgs = build_stable_prefix()
    msgs.extend(history)

    # 本轮循环组
    group = [{"role": "user", "content": player_input}]
    if worldbook_key:
        group.append({"role": "user", "content": FX.WORLDBOOK[worldbook_key]})
    group.append({"role": "user", "content":
                  "【上一轮变量变化】上一轮结算后发生变化的变量及最新值(路径相对于状态根),"
                  "其余变量未变。写本轮正文时以此为准。\n" + delta_lines(last_delta)})
    msgs.extend(group)

    # 队尾指令帧:4 段拼接(docs/05 §1 装配表)
    frame = "\n\n".join([
        "【行文规范 · 本轮生效】\n" + FX.WRITING_RULES,
        "【附加要求】\n" + FX.POST_HISTORY,
        "【状态协议禁令】\n" + FX.MVU_BAN,
        "【本轮演出指引】\n" + player_input,
    ])
    msgs.append({"role": "user", "content": frame})
    return msgs, group


# ==========================================================================
# 3. 结算调用的装配(docs/05 §4.4)
# ==========================================================================
OUT_SPEC_ABS = """【输出要求】只输出一个 JSON 对象,不要解释,不要代码块围栏,不要任何额外文字:
{"delta": {"<JSON Pointer>": <新值>, ...}, "posture": "<=100字的现场姿态", "reason": "一句话依据"}

关于 delta 的语义(必须严格遵守):
- 键是上面【变量路径与类型索引】里出现过的 JSON Pointer 路径,不得发明新路径。
- 值是该路径**结算后的新值(绝对值)**,不是增量。
  例:生命从 60 掉到 48,写 "/冒险者/生命": 48,**不要**写 -12。
- 本轮没有变化的路径**不要出现**在 delta 里。若本轮什么都没变,delta 就是 {}。
- 数值写成数字,不要写 "+3" 这类字符串。"""

OUT_SPEC_INC = """【输出要求】只输出一个 JSON 对象,不要解释,不要代码块围栏,不要任何额外文字:
{"delta": {"<JSON Pointer>": <增量>, ...}, "posture": "<=100字的现场姿态", "reason": "一句话依据"}

关于 delta 的语义(必须严格遵守):
- 键是上面【变量路径与类型索引】里出现过的 JSON Pointer 路径,不得发明新路径。
- 数值路径的值是**本轮的增量**,不是新值。
  例:生命从 60 掉到 48,写 "/冒险者/生命": -12,**不要**写 48。
- 非数值路径(字符串/布尔/数组)的值仍然写新值。
- 本轮没有变化的路径**不要出现**在 delta 里。若本轮什么都没变,delta 就是 {}。
- 数值写成数字,不要写 "+3" 这类字符串。"""


def build_settle_messages(state, player_input, narrative, rules, semantics="abs"):
    spec = OUT_SPEC_ABS if semantics == "abs" else OUT_SPEC_INC
    system = "\n\n".join([
        rules,
        VAR_CATALOG_HINT % json.dumps(FX.VAR_CATALOG, ensure_ascii=False),
    ])
    return [
        {"role": "system", "content": system},
        {"role": "user", "content": "【当前权威状态 · 全量】这是客户端状态机的真实值,"
                                    "不是模型的回忆,以此为基准计算本轮变化。\n"
                                    + snapshot_lines(state)},
        {"role": "user", "content": "【本轮玩家输入】\n" + player_input},
        {"role": "user", "content": "【本轮正文】\n" + narrative},
        {"role": "user", "content": spec},
    ]


# ==========================================================================
# 4. 正文纯净度检查(D1)
# ==========================================================================
HARD_PATTERNS = [
    ("代码块围栏", re.compile(r"```")),
    ("initvar 标签", re.compile(r"</?initvar", re.I)),
    ("UpdateVariable 标签", re.compile(r"</?UpdateVariable", re.I)),
    ("state/status 标签", re.compile(r"</?(state|mvu-status|status)\b", re.I)),
    ("JSON 对象", re.compile(r'\{\s*["\u201c]')),
    ("JSON Pointer 赋值", re.compile(r"/[^\s/]{1,12}/[^\s/]{1,12}\s*[=:]")),
    ("Markdown 表格行", re.compile(r"^[ \t]*\|.*\|", re.M)),
]

FIELD_WORDS = "生命|生命上限|攻击|防御|闪避|暴击|金币|经验|升级所需|等级|强化|总轮数|当前轮|每口恢复"
SOFT_PATTERNS = [
    ("字段名+冒号+数字", re.compile(r"(%s)\s*[:\uff1a]\s*[+\-\u2212]?\d" % FIELD_WORDS)),
    ("字段名+增减记号", re.compile(r"(%s)\s*[+\-\u2212]\s*\d" % FIELD_WORDS)),
    ("分数式数值", re.compile(r"\d+\s*/\s*\d+")),
    ("【状态】小标题", re.compile(r"[\u3010\[](状态|数值|变量|属性|面板)[\u3011\]]")),
]


def purity_check(text):
    hard, soft = [], []
    for name, pat in HARD_PATTERNS:
        m = pat.search(text or "")
        if m:
            hard.append({"rule": name, "sample": (m.group(0) or "")[:40]})
    for name, pat in SOFT_PATTERNS:
        m = pat.search(text or "")
        if m:
            soft.append({"rule": name, "sample": (m.group(0) or "")[:40]})
    return hard, soft


# ==========================================================================
# 5. 结算结果解析与校验(D2 / D6)
# ==========================================================================
def parse_settle(text):
    """返回 (obj, how, err)。how 记录「是否需要容错才解析成功」—— 这本身就是结论。"""
    if not text:
        return None, None, "空响应"
    t = text.strip()
    # 直接解
    try:
        return json.loads(t), "直接解析", None
    except Exception:
        pass
    # 剥代码块围栏
    m = re.search(r"```(?:json)?\s*(.*?)```", t, re.S)
    if m:
        try:
            return json.loads(m.group(1).strip()), "剥代码块围栏后解析", None
        except Exception:
            pass
    # 抓第一个平衡的大括号块
    start = t.find("{")
    if start >= 0:
        depth, in_str, esc = 0, False, False
        for i in range(start, len(t)):
            c = t[i]
            if in_str:
                if esc:
                    esc = False
                elif c == "\\":
                    esc = True
                elif c == '"':
                    in_str = False
                continue
            if c == '"':
                in_str = True
            elif c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    try:
                        return json.loads(t[start:i + 1]), "抓平衡括号块后解析", None
                    except Exception as e:
                        return None, None, "括号块仍非法 JSON: %r" % (e,)
    return None, None, "找不到 JSON 对象"


NONNEG_PATHS = {"/冒险者/生命", "/冒险者/经验", "/冒险者/等级", "/资源/金币",
                "/水壶/当前", "/水壶/上限", "/装备/强化", "/地牢/当前轮", "/地牢/总轮数"}
HP_DELTA_LIMIT = 60  # 单轮生命变化绝对值上限(= 生命上限),超了就是暴走


def check_schema(obj):
    errs = []
    if not isinstance(obj, dict):
        return ["顶层不是对象"]
    d = obj.get("delta")
    if d is None:
        errs.append("缺少 delta 字段")
    elif not isinstance(d, dict):
        errs.append("delta 不是对象")
    if not isinstance(obj.get("posture"), str) or not obj.get("posture", "").strip():
        errs.append("posture 缺失或不是非空字符串")
    if not isinstance(obj.get("reason"), str) or not obj.get("reason", "").strip():
        errs.append("reason 缺失或不是非空字符串")
    extra = set(obj.keys()) - {"delta", "posture", "reason"}
    if extra:
        errs.append("多出未约定字段: %s" % ", ".join(sorted(extra)))
    return errs


def validate_delta(delta, state, semantics="abs"):
    """
    对照 docs/05 §4.6 的客户端校验规则。返回 (violations, clean_delta_as_abs)。
    clean_delta_as_abs 一律折算成「绝对新值」,方便两种语义统一比较。
    """
    v, clean = [], {}
    if not isinstance(delta, dict):
        return [{"code": "DELTA_NOT_OBJECT", "detail": repr(delta)[:80]}], {}

    for path, val in delta.items():
        if not isinstance(path, str) or not path.startswith("/"):
            v.append({"code": "BAD_POINTER", "path": str(path)[:40]})
            continue
        if path not in FX.LEGAL_PATHS:
            v.append({"code": "UNKNOWN_PATH", "path": path})
            continue

        old, _ = ptr_get(state, path)
        want = FX.type_name(old)

        # 「+3」这类字符串 —— docs/05 §4.4.1 第 3 条明令禁止
        if want == "number" and isinstance(val, str):
            v.append({"code": "STRING_NUMBER", "path": path, "detail": val[:20]})
            try:
                val = float(val.replace("+", "").replace("\u2212", "-"))
                if val == int(val):
                    val = int(val)
            except Exception:
                continue

        got = FX.type_name(val)
        if want == "number" and got != "number":
            v.append({"code": "TYPE_MISMATCH", "path": path,
                      "detail": "期望 number,得到 %s" % got})
            continue
        if want in ("string", "boolean", "array") and got != want:
            v.append({"code": "TYPE_MISMATCH", "path": path,
                      "detail": "期望 %s,得到 %s" % (want, got)})
            continue

        if want == "number":
            newval = (old or 0) + val if semantics == "inc" else val
            # 语义搞错的强信号:声明写绝对值,却给了一个非负字段的负数
            if semantics == "abs" and path in NONNEG_PATHS and isinstance(val, (int, float)) and val < 0:
                v.append({"code": "LOOKS_LIKE_INCREMENT", "path": path,
                          "detail": "要求绝对新值,却给了负数 %s" % val})
            if path == "/冒险者/生命":
                cap, _ = ptr_get(state, "/冒险者/生命上限")
                cap = cap if isinstance(cap, (int, float)) else 60
                if abs(newval - (old or 0)) > HP_DELTA_LIMIT:
                    v.append({"code": "DELTA_TOO_LARGE", "path": path,
                              "detail": "%s -> %s" % (old, newval)})
                if newval < 0 or newval > cap:
                    v.append({"code": "CLAMPED", "path": path,
                              "detail": "%s 超出 [0, %s]" % (newval, cap)})
                    newval = max(0, min(cap, newval))
            elif path in NONNEG_PATHS and newval < 0:
                v.append({"code": "CLAMPED", "path": path, "detail": "%s < 0" % newval})
                newval = 0
            clean[path] = newval
        else:
            if want == "string" and path == "/地牢/本轮房间":
                if val not in ("门厅", "小怪群", "精英怪", "属性提升", "铁匠铺", "泉水", "塔底"):
                    v.append({"code": "ILLEGAL_ENUM", "path": path, "detail": str(val)[:20]})
            clean[path] = val

        if clean.get(path) == old:
            v.append({"code": "NO_OP", "path": path, "detail": "新值与旧值相同,本不该出现在 delta 里"})

    return v, clean


def check_assertions(spec, clean_abs, state):
    """D6:must_change / must_not_change / direction 三类断言。"""
    res = {"must_change": [], "must_not_change": [], "direction": [],
           "passed": 0, "total": 0}

    for p in spec.get("must_change") or []:
        old, _ = ptr_get(state, p)
        hit = p in clean_abs and clean_abs[p] != old
        res["must_change"].append({"path": p, "ok": hit,
                                   "got": clean_abs.get(p, "(缺失)"), "old": old})
        res["total"] += 1
        res["passed"] += 1 if hit else 0

    for p in spec.get("must_not_change") or []:
        old, _ = ptr_get(state, p)
        bad = p in clean_abs and clean_abs[p] != old
        res["must_not_change"].append({"path": p, "ok": not bad,
                                       "got": clean_abs.get(p, "(未出现 ✅)"), "old": old})
        res["total"] += 1
        res["passed"] += 0 if bad else 1

    for p, sign in (spec.get("direction") or {}).items():
        old, _ = ptr_get(state, p)
        new = clean_abs.get(p)
        if not isinstance(new, (int, float)) or not isinstance(old, (int, float)):
            ok = False
            got = "(非数值或缺失)"
        else:
            diff = new - old
            ok = diff > 0 if sign == "+" else diff < 0
            got = "%s -> %s (%+d)" % (old, new, diff)
        res["direction"].append({"path": p, "want": sign, "ok": ok, "got": got})
        res["total"] += 1
        res["passed"] += 1 if ok else 0

    return res


# ==========================================================================
# 6. 调用层
# ==========================================================================
def _or_usage_hdr(cfg, body):
    """OpenRouter 要显式要 usage 才会回 cost(真金白银数字,别靠估算)。"""
    if "openrouter" in cfg["url"]:
        body["usage"] = {"include": True}
    return body


def sse_text(result):
    """从 post_sse 的行记录里拼出 content,并单独统计 reasoning 片段。"""
    content, reasoning = [], []
    finish = None
    for _, line in result["lines"]:
        if not line.startswith("data:"):
            continue
        payload = line[5:].strip()
        if payload.startswith("[") and payload.endswith("]"):
            continue
        o = jget(payload)
        if not isinstance(o, dict):
            continue
        for ch in o.get("choices") or []:
            d = ch.get("delta") or {}
            if isinstance(d.get("content"), str):
                content.append(d["content"])
            for rk in ("reasoning", "reasoning_content"):
                if isinstance(d.get(rk), str):
                    reasoning.append(d[rk])
            if ch.get("finish_reason"):
                finish = ch["finish_reason"]
    return "".join(content), "".join(reasoning), finish


def assert_messages_wellformed(messages, where):
    """
    🔴 发送前守卫(2026-10-18 新增,起因见 build_narrative_messages 的 bug #1)。

    教训:一个「把 dict 当 list 展开」的低级错误,在本地看起来完全正常,
    只有真发请求、且只有第 2 轮起才会炸成 HTTP 400,白烧了一次全量跑。
    所以装配结果必须在**离线**就被结构校验,而不是交给服务端去报错。

    任何一条不合法就立刻抛异常 —— 宁可跑不起来,也不要烧钱跑出一份废报告。
    """
    if not isinstance(messages, list) or not messages:
        raise TypeError("[%s] messages 必须是非空 list,实际 %r" % (where, type(messages)))
    for i, m in enumerate(messages):
        if not isinstance(m, dict):
            raise TypeError(
                "[%s] messages[%d] 必须是 dict,实际是 %s(值=%r)。"
                "典型成因:对 dict 用了 list.extend(),把键名当成了元素。"
                % (where, i, type(m).__name__, m))
        if m.get("role") not in ("system", "user", "assistant"):
            raise ValueError("[%s] messages[%d] 的 role 非法:%r" % (where, i, m.get("role")))
        if not isinstance(m.get("content"), str):
            raise TypeError("[%s] messages[%d] 的 content 必须是 str,实际 %s"
                            % (where, i, type(m.get("content")).__name__))
    return messages


def call_narrative(cfg, key, messages, tag, provider):
    """叙事调用:流式 · **不传 max_tokens** · 关思考。"""
    assert_messages_wellformed(messages, "叙事调用 " + tag)
    body = {
        "model": cfg["model"],
        "messages": messages,
        "stream": True,
        "stream_options": {"include_usage": True},
    }
    body.update(cfg["reasoning_off"])
    _or_usage_hdr(cfg, body)

    t0 = time.time()
    r = post_sse(cfg, key, body, timeout=NARRATIVE_TIMEOUT)
    text, reasoning, finish = sse_text(r)
    raw = "\n".join("[%6dms] %s" % (ms, l) for ms, l in r["lines"])
    write_out(provider, "%s_raw.txt" % tag, raw or (r.get("error") or ""))
    write_out(provider, "%s_text.txt" % tag, text)
    usage = extract_usage(raw) or {}
    return {
        "ok": bool(text) and r.get("status") == 200,
        "status": r.get("status"),
        "error": r.get("error"),
        "text": text,
        "chars": len(text),
        "reasoning_chars": len(reasoning),
        "finish_reason": finish,
        "first_data_ms": r.get("first_data_ms"),
        "total_ms": r.get("total_ms"),
        "saw_done": r.get("saw_done_sentinel"),
        "usage": usage,
        "elapsed_s": round(time.time() - t0, 2),
    }


def call_settle(cfg, key, messages, tag, provider, model=None):
    """结算调用:非流式 · max_tokens=512 · 关思考 · temperature=0.2。"""
    assert_messages_wellformed(messages, "结算调用 " + tag)
    body = {
        "model": model or cfg["model"],
        "messages": messages,
        "stream": False,
        "max_tokens": SETTLE_MAX_TOKENS,
        "temperature": SETTLE_TEMPERATURE,
    }
    body.update(cfg["reasoning_off"])
    _or_usage_hdr(cfg, body)

    t0 = time.time()
    status, _hdr, raw, el = post_json(cfg, key, body, timeout=SETTLE_TIMEOUT)
    write_out(provider, "%s_raw.json" % tag, raw)
    o = jget(raw) or {}
    text, finish = "", None
    for ch in o.get("choices") or []:
        msg = ch.get("message") or {}
        if isinstance(msg.get("content"), str):
            text += msg["content"]
        finish = ch.get("finish_reason") or finish
    usage = o.get("usage") or extract_usage(raw) or {}
    return {
        "ok": status == 200 and bool(text),
        "status": status,
        "text": text,
        "finish_reason": finish,
        "usage": usage,
        "elapsed_s": round(el, 2),
        "raw_head": raw[:300] if status != 200 else None,
    }


def acc_cost(report, kind, usage):
    """把每次调用的 token 与 cost 累到 D4 表里。"""
    t = report.setdefault("cost", {}).setdefault(kind, {
        "calls": 0, "prompt": 0, "completion": 0, "reasoning": 0,
        "cached": 0, "cost_usd": 0.0, "cost_known": 0})
    t["calls"] += 1
    u = usage or {}
    ctd = u.get("completion_tokens_details") or {}
    ptd = u.get("prompt_tokens_details") or {}
    t["prompt"] += u.get("prompt_tokens") or 0
    t["completion"] += u.get("completion_tokens") or 0
    t["reasoning"] += ctd.get("reasoning_tokens") or 0
    t["cached"] += (ptd.get("cached_tokens") or u.get("prompt_cache_hit_tokens") or 0)
    if isinstance(u.get("cost"), (int, float)):
        t["cost_usd"] += float(u["cost"])
        t["cost_known"] += 1


# ==========================================================================
# 7. S · 主会话(D1 + D2 + D6)
# ==========================================================================
def run_session(provider, cfg, key, report, rounds, reuse):
    say("\n[S] 主会话:%d 轮「叙事 -> 结算 -> 应用 delta」(方案 C 全链路)" % rounds)
    R = report.setdefault("session", {"turns": [], "reused_narratives": False})

    cached = None
    if reuse and os.path.exists(NARR_CACHE):
        try:
            cached = json.load(open(NARR_CACHE, encoding="utf-8"))
            say("     复用已缓存正文 %s(不再花钱生成叙事)" % os.path.basename(NARR_CACHE))
            R["reused_narratives"] = True
        except Exception:
            cached = None

    state = deep_copy(FX.INIT_STATE)
    history = []
    last_delta = {}
    narr_cache_out = []

    for spec in FX.SCRIPT[:rounds]:
        n = spec["turn"]
        say("   --- 第 %d 轮 --- %s" % (n, spec["note"][:48]))
        snapshot_in = deep_copy(state)

        messages, group = build_narrative_messages(
            history, spec["input"], spec.get("worldbook"), last_delta)

        # ---------- 叙事调用(D1) ----------
        # 🔴 2026-10-18:缓存里可能存着「上一次跑失败留下的空正文」(bug #1 的残骸)。
        # 空正文绝不能当有效缓存复用,否则重跑时第 2~4 轮会继续空着,bug 像是没修好。
        _c_ok = bool(cached and len(cached.get("turns") or []) >= n
                     and (cached["turns"][n - 1].get("narrative") or "").strip())
        if cached and len(cached.get("turns") or []) >= n and not _c_ok:
            say("       ⚠️ 缓存里第 %d 轮正文为空(上次失败的残骸),忽略缓存,重新请求" % n)
        if _c_ok:
            c = cached["turns"][n - 1]
            narr = {"ok": True, "text": c["narrative"], "chars": len(c["narrative"]),
                    "usage": c.get("usage") or {}, "from_cache": True,
                    "finish_reason": c.get("finish_reason"),
                    "first_data_ms": c.get("first_data_ms"),
                    "total_ms": c.get("total_ms"),
                    "reasoning_chars": c.get("reasoning_chars", 0)}
            say("       叙事: 取自缓存, %d 字" % narr["chars"])
        else:
            narr = call_narrative(cfg, key, messages, "s_t%d_narr" % n, provider)
            acc_cost(report, "叙事调用", narr["usage"])
            if not narr["ok"]:
                say("       🔴 叙事调用失败 status=%s %s"
                    % (narr["status"], (narr.get("error") or "")[:120]))
            else:
                say("       叙事: %d 字, 首字节 %sms, 总 %sms, finish=%s, 思考 %d 字"
                    % (narr["chars"], narr["first_data_ms"], narr["total_ms"],
                       narr["finish_reason"], narr["reasoning_chars"]))

        hard, soft = purity_check(narr.get("text") or "")
        if hard:
            say("       🔴 正文硬污染: %s" % ", ".join(h["rule"] for h in hard))
        elif soft:
            say("       ⚠️ 正文软污染: %s" % ", ".join(s["rule"] for s in soft))
        else:
            say("       ✅ 正文纯净(无 JSON / 标签 / 表格 / 数值)")

        turn = {
            "turn": n, "input": spec["input"], "note": spec["note"],
            "worldbook": spec.get("worldbook"),
            "snapshot_in": snapshot_in,
            "prompt_messages": len(messages),
            "narrative_ok": narr["ok"], "narrative_chars": narr.get("chars"),
            "narrative_from_cache": narr.get("from_cache", False),
            "narrative_finish": narr.get("finish_reason"),
            "narrative_first_ms": narr.get("first_data_ms"),
            "narrative_total_ms": narr.get("total_ms"),
            "narrative_reasoning_chars": narr.get("reasoning_chars"),
            "narrative_excerpt": (narr.get("text") or "")[:400],
            "purity_hard": hard, "purity_soft": soft,
            "narrative_error": narr.get("error"),
        }
        narr_cache_out.append({
            "turn": n, "narrative": narr.get("text") or "",
            "usage": narr.get("usage") or {},
            "finish_reason": narr.get("finish_reason"),
            "first_data_ms": narr.get("first_data_ms"),
            "total_ms": narr.get("total_ms"),
            "reasoning_chars": narr.get("reasoning_chars", 0),
            "snapshot_in": snapshot_in,
        })

        # ---------- 结算调用(D2 + D6) ----------
        if narr.get("text"):
            smsgs = build_settle_messages(state, spec["input"], narr["text"],
                                          FX.SETTLE_RULES_STRICT, "abs")
            st = call_settle(cfg, key, smsgs, "s_t%d_settle" % n, provider)
            acc_cost(report, "结算调用", st["usage"])
            turn.update(score_settle(st, spec, state, "abs"))
            if turn["parsed"]:
                for p, val in (turn["clean_delta"] or {}).items():
                    ptr_set(state, p, val)
                last_delta = turn["clean_delta"] or {}
            else:
                last_delta = {}
            say("       结算: %s | schema %s | 断言 %d/%d | 违规 %d | %ss"
                % ("可解析 ✅" if turn["parsed"] else "解析失败 🔴",
                   "合法 ✅" if not turn["schema_errors"] else "不合法 🔴",
                   turn["assert_passed"], turn["assert_total"],
                   len(turn["violations"]), st["elapsed_s"]))
            if not turn["parsed"]:
                say("         status=%s finish=%s err=%s"
                    % (st["status"], st["finish_reason"], turn["parse_error"]))
        else:
            turn.update({"parsed": False, "parse_error": "没有正文,跳过结算",
                         "schema_errors": [], "violations": [], "delta": {},
                         "clean_delta": {}, "assert_passed": 0, "assert_total": 0,
                         "assertions": {}, "parse_how": None, "posture": None,
                         "reason": None, "settle_status": None,
                         "settle_finish": None, "settle_elapsed_s": None})
            last_delta = {}

        turn["state_after"] = deep_copy(state)
        R["turns"].append(turn)
        history.extend(group)
        history.append({"role": "assistant", "content": narr.get("text") or "(生成失败)"})

    R["final_state"] = deep_copy(state)
    if narr_cache_out and not R["reused_narratives"]:
        os.makedirs(OUT_DIR, exist_ok=True)
        with open(NARR_CACHE, "w", encoding="utf-8") as f:
            f.write(redact(json.dumps({"provider": provider, "model": cfg["model"],
                                       "turns": narr_cache_out},
                                      ensure_ascii=False, indent=1)))
        say("     叙事正文已缓存 -> %s(跑对照组时会自动复用,不再花钱)"
            % os.path.normpath(NARR_CACHE))


def score_settle(st, spec, state, semantics):
    """把一次结算调用打成分。D2/D5/D7 共用。"""
    obj, how, err = parse_settle(st.get("text") or "")
    out = {
        "settle_status": st.get("status"),
        "settle_finish": st.get("finish_reason"),
        "settle_elapsed_s": st.get("elapsed_s"),
        "settle_text_head": (st.get("text") or "")[:200],
        "parsed": obj is not None,
        "parse_how": how,
        "parse_error": err,
    }
    if obj is None:
        out.update({"schema_errors": ["未能解析"], "violations": [], "delta": {},
                    "clean_delta": {}, "assertions": {}, "assert_passed": 0,
                    "assert_total": len(spec.get("must_change") or [])
                    + len(spec.get("must_not_change") or [])
                    + len(spec.get("direction") or {}),
                    "posture": None, "reason": None})
        return out

    out["schema_errors"] = check_schema(obj)
    out["posture"] = obj.get("posture")
    out["posture_chars"] = len(obj.get("posture") or "")
    out["reason"] = obj.get("reason")
    delta = obj.get("delta") if isinstance(obj.get("delta"), dict) else {}
    out["delta"] = delta
    viol, clean = validate_delta(delta, state, semantics)
    out["violations"] = viol
    out["clean_delta"] = clean
    a = check_assertions(spec, clean, state)
    out["assertions"] = a
    out["assert_passed"] = a["passed"]
    out["assert_total"] = a["total"]
    return out


# ==========================================================================
# 8. 对照组:D3(换模型) / D5(松规则) / D7(增量语义)
# ==========================================================================
def load_narratives():
    if not os.path.exists(NARR_CACHE):
        return None
    try:
        return json.load(open(NARR_CACHE, encoding="utf-8"))
    except Exception:
        return None


def run_variant(provider, cfg, key, report, rounds, name, label,
                rules, semantics, model=None):
    """
    用**同一批正文 + 同一批输入快照**重跑结算,只改一个变量。
    快照取自主会话记录的 snapshot_in,保证对照组之间输入完全一致。
    """
    say("\n[%s] %s" % (name.upper(), label))
    narrs = load_narratives()
    if not narrs or not narrs.get("turns"):
        say("     ⚠️ 找不到 %s —— 先跑一次 --only s 生成正文缓存,本项跳过。"
            % os.path.basename(NARR_CACHE))
        report.setdefault("variants", {})[name] = {"skipped": "缺少正文缓存"}
        return

    V = {"label": label, "model": model or cfg["model"], "semantics": semantics,
         "rules": "STRICT" if rules is FX.SETTLE_RULES_STRICT else "LOOSE",
         "turns": []}
    for spec in FX.SCRIPT[:rounds]:
        n = spec["turn"]
        rec = None
        for t in narrs["turns"]:
            if t.get("turn") == n:
                rec = t
                break
        if not rec or not rec.get("narrative"):
            say("   第 %d 轮:缓存里没有正文,跳过" % n)
            continue
        state = rec.get("snapshot_in") or deep_copy(FX.INIT_STATE)
        smsgs = build_settle_messages(state, spec["input"], rec["narrative"],
                                      rules, semantics)
        st = call_settle(cfg, key, smsgs, "%s_t%d_settle" % (name, n),
                         provider, model=model)
        acc_cost(report, "结算调用 · %s" % name.upper(), st["usage"])
        row = score_settle(st, spec, state, semantics)
        row["turn"] = n
        V["turns"].append(row)
        say("   第 %d 轮: %s | schema %s | 断言 %d/%d | 违规 %d"
            % (n, "可解析 ✅" if row["parsed"] else "解析失败 🔴",
               "合法 ✅" if not row["schema_errors"] else "不合法 🔴",
               row["assert_passed"], row["assert_total"], len(row["violations"])))
    report.setdefault("variants", {})[name] = V


# ==========================================================================
# 9. 报告
# ==========================================================================
def pct(a, b):
    return "—" if not b else "%d%% (%d/%d)" % (round(100.0 * a / b), a, b)


def agg(turns):
    """把若干轮结算汇总成一行指标。"""
    n = len(turns)
    return {
        "n": n,
        "parsed": sum(1 for t in turns if t.get("parsed")),
        "direct": sum(1 for t in turns if t.get("parse_how") == "直接解析"),
        "schema_ok": sum(1 for t in turns if t.get("parsed") and not t.get("schema_errors")),
        "assert_p": sum(t.get("assert_passed") or 0 for t in turns),
        "assert_t": sum(t.get("assert_total") or 0 for t in turns),
        "viol": sum(len(t.get("violations") or []) for t in turns),
        "clean_turns": sum(1 for t in turns if t.get("parsed") and not t.get("violations")),
    }


def render_report(report):
    L = []
    A = L.append
    A("# Mirage · M0 双通道实测报告(REPORT4)")
    A("")
    A("| 项 | 值 |")
    A("|---|---|")
    A("| 时间 | %s |" % report.get("ts"))
    A("| 服务商 | `%s` |" % report.get("provider"))
    A("| 叙事模型 | `%s` |" % report.get("model"))
    A("| 结算模型(主) | `%s` |" % report.get("model"))
    A("| 轮数 | %s |" % report.get("rounds"))
    A("| 跑了哪些项 | %s |" % ", ".join(report.get("ran") or []) or "—")
    A("| 验证目标 | `V-12`:docs/05 §4.0 的方案 C(叙事/结算双通道)能不能用 |")
    A("| 测试材料 | 真实角色卡 `DiLaoJSON.json`(E1),见 `tools/m0_fixture.py` |")
    A("")

    if report.get("step_errors"):
        A("## ⚠️ 本次有 %d 项异常" % len(report["step_errors"]))
        A("")
        for e in report["step_errors"]:
            A("- `%s` -> `%s`" % (e.get("step"), e.get("error")))
        A("")

    # ---------------- 结论速览 ----------------
    S = report.get("session") or {}
    turns = S.get("turns") or []
    settle_turns = [t for t in turns if t.get("settle_status") is not None]
    g = agg(settle_turns)
    # 🔴 2026-10-18:纯净度的分母只能是「真的拿到了正文」的轮次。
    # 上一版把 3 次 HTTP 400 失败也算进分母,还给它们标了「✅ 无污染」,
    # 于是报出「硬污染 0% (0/4)」—— 实际只有 1 个有效样本。这种报告比没有更危险。
    ok_turns = [t for t in turns if (t.get("narrative_excerpt") or "").strip()
                and t.get("narrative_ok")]
    bad_turns = [t for t in turns if t not in ok_turns]
    hard_bad = sum(1 for t in ok_turns if t.get("purity_hard"))
    soft_bad = sum(1 for t in ok_turns if t.get("purity_soft"))
    nt = len(ok_turns)

    A("## 0. 结论速览")
    A("")
    if bad_turns:
        A("> 🔴 **本次有 %d/%d 轮叙事调用失败,下面所有比率的分母只有 %d 个有效样本,"
          "不足以作为 `V-12` 的判定依据。**" % (len(bad_turns), len(turns), nt))
        A("> 失败轮次:%s" % ", ".join("第 %d 轮" % t["turn"] for t in bad_turns))
        for t in bad_turns:
            A("> - 第 %d 轮:`%s`" % (t["turn"], (t.get("narrative_error") or "(未记录错误)")[:200]))
        A("")
    # 🔴 样本不足时不允许出现「✅ 通过」—— 1 个样本的 0% 不是结论。
    _SAMPLE_MIN = len(turns)

    def verdict(ok, n_sample, fail_label=None):
        """fail_label 用于「参考」类指标：没达成不等于不及格，不该打 🔴 未通过。"""
        if bad_turns or n_sample < _SAMPLE_MIN:
            return "⚠️ 样本不足(%d/%d),不作数" % (n_sample, _SAMPLE_MIN)
        if ok:
            return "✅ 通过"
        return fail_label or "🔴 未通过"

    A("| 测试项 | 及格线 | 实测 | 判定 |")
    A("|---|---|---|---|")
    if nt:
        A("| **D1 正文硬污染率** | 0%% | %s | %s |"
          % (pct(hard_bad, nt), verdict(hard_bad == 0, nt)))
        A("| D1 正文软污染率 | 参考 | %s | %s |"
          % (pct(soft_bad, nt), verdict(soft_bad == 0, nt, "⚠️ 需加强禁令")))
    if g["n"]:
        A("| **D2 结算可解析率** | ≥95%% | %s | %s |"
          % (pct(g["parsed"], g["n"]), verdict(g["parsed"] == g["n"], g["n"])))
        A("| D2 其中「不需容错即可直接解析」 | 参考 | %s | %s |"
          % (pct(g["direct"], g["n"]), verdict(g["direct"] == g["n"], g["n"], "⚠️ 需要剥围栏兜底")))
        A("| **D2 schema 合法率** | ≥95%% | %s | %s |"
          % (pct(g["schema_ok"], g["n"]),
             verdict(g["schema_ok"] == g["n"], g["n"])))
        A("| **D6 断言通过率** | 参考(定 Prompt 迭代方向) | %s | %s |"
          % (pct(g["assert_p"], g["assert_t"]),
             verdict(g["assert_p"] == g["assert_t"], g["n"], "⚠️ 有偏差,见 §3")))
        A("| D6 零违规轮次 | 参考 | %s | — |" % pct(g["clean_turns"], g["n"]))
    A("")

    # ---------------- D1 ----------------
    if turns:
        A("## 1. D1 · 叙事调用的正文纯净度")
        A("")
        A("> 装配:`docs/05 §1` v1.1(稳定前缀 5 条 + 每轮循环组 + 队尾指令帧 4 段)")
        A("> 参数:**流式 · 不传 `max_tokens`(E1 硬约束 1)· 关思考**")
        A("")
        A("| 轮 | messages 条数 | 正文字数 | 首字节 | 总耗时 | finish | 思考字数 | 硬污染 | 软污染 |")
        A("|---|---|---|---|---|---|---|---|---|")
        for t in turns:
            A("| %d%s | %s | %s | %sms | %sms | `%s` | %s | %s | %s |"
              % (t["turn"], "(缓存)" if t.get("narrative_from_cache") else "",
                 t.get("prompt_messages"), t.get("narrative_chars"),
                 t.get("narrative_first_ms"), t.get("narrative_total_ms"),
                 t.get("narrative_finish"), t.get("narrative_reasoning_chars"),
                 ("🔴 " + "/".join(h["rule"] for h in t["purity_hard"])
                  if t.get("purity_hard")
                  else ("— 调用失败" if t in bad_turns else "✅ 无")),
                 ("⚠️ " + "/".join(s["rule"] for s in t["purity_soft"])
                  if t.get("purity_soft")
                  else ("— 调用失败" if t in bad_turns else "✅ 无"))))
        A("")
        for t in turns:
            if t.get("purity_hard") or t.get("purity_soft"):
                A("**第 %d 轮命中样本**" % t["turn"])
                A("")
                for h in (t.get("purity_hard") or []):
                    A("- 🔴 `%s` -> `%s`" % (h["rule"], h["sample"]))
                for s in (t.get("purity_soft") or []):
                    A("- ⚠️ `%s` -> `%s`" % (s["rule"], s["sample"]))
                A("")
        A("<details><summary>各轮正文节选(前 400 字)</summary>")
        A("")
        for t in turns:
            A("**第 %d 轮** · 玩家输入:%s" % (t["turn"], t["input"][:60]))
            A("")
            A("```text")
            _ex = (t.get("narrative_excerpt") or "").strip()
            if not _ex:
                _ex = "(本轮没有正文 —— 调用失败)\n错误：%s" % (t.get("narrative_error") or "未记录")
            A(_ex.replace("```", "'''"))
            A("```")
            A("")
        A("</details>")
        A("")

    # ---------------- D2 / D6 ----------------
    if settle_turns:
        A("## 2. D2 · 结算调用的可解析率与 schema 合法率")
        A("")
        A("> 参数:**非流式 · `max_tokens=512` · `temperature=0.2` · 关思考**(docs/05 §4.4)")
        A("> 规则:`SETTLE_RULES_STRICT`(含 docs/05 §4.4.1 抄自真 ST 的四条约束)")
        A("> delta 语义:**绝对新值**")
        A("")
        A("| 轮 | HTTP | finish | 耗时 | 解析方式 | schema | posture 字数 | delta 项数 | 违规 |")
        A("|---|---|---|---|---|---|---|---|---|")
        for t in settle_turns:
            A("| %d | %s | `%s` | %ss | %s | %s | %s | %s | %s |"
              % (t["turn"], t.get("settle_status"), t.get("settle_finish"),
                 t.get("settle_elapsed_s"),
                 t.get("parse_how") or ("🔴 " + str(t.get("parse_error"))[:40]),
                 "✅" if t.get("parsed") and not t.get("schema_errors")
                 else "🔴 " + "; ".join(t.get("schema_errors") or []),
                 t.get("posture_chars", "—"), len(t.get("delta") or {}),
                 len(t.get("violations") or []) or "✅ 0"))
        A("")

        A("### 2.1 每轮结算的真实产出")
        A("")
        for t in settle_turns:
            A("#### 第 %d 轮 · %s" % (t["turn"], t.get("note", "")))
            A("")
            A("- **玩家输入**:%s" % t["input"])
            A("- **结算器给的 delta**:`%s`"
              % json.dumps(t.get("delta") or {}, ensure_ascii=False))
            if t.get("clean_delta") != t.get("delta"):
                A("- **客户端校验后(折算成绝对新值)**:`%s`"
                  % json.dumps(t.get("clean_delta") or {}, ensure_ascii=False))
            A("- **posture**:%s" % (t.get("posture") or "(无)"))
            A("- **reason**:%s" % (t.get("reason") or "(无)"))
            if t.get("violations"):
                A("- **违规**:")
                for v in t["violations"]:
                    A("  - `%s` %s %s" % (v.get("code"), v.get("path", ""),
                                          v.get("detail", "")))
            if not t.get("parsed"):
                A("- 🔴 **原始响应开头**:`%s`" % (t.get("settle_text_head") or "")[:200])
            A("")

        A("## 3. D6 · 断言驱动的结算质量")
        A("")
        A("> `must_change` = 正文明确发生了对应事件,这条路径**必须**变;")
        A("> `must_not_change` = 没有任何正文依据,**不该**变(DZMM 的「无中生有」就栽在这);")
        A("> `direction` = 变化方向(DZMM 的「只增不减」就栽在这)。")
        A("")
        A("| 轮 | 断言 | 路径 | 期望 | 实际 | 判定 |")
        A("|---|---|---|---|---|---|")
        for t in settle_turns:
            a = t.get("assertions") or {}
            for x in a.get("must_change") or []:
                A("| %d | must_change | `%s` | 必须变(原 `%s`) | `%s` | %s |"
                  % (t["turn"], x["path"], x["old"], x["got"], "✅" if x["ok"] else "🔴"))
            for x in a.get("must_not_change") or []:
                A("| %d | must_not_change | `%s` | 保持 `%s` | `%s` | %s |"
                  % (t["turn"], x["path"], x["old"], x["got"], "✅" if x["ok"] else "🔴"))
            for x in a.get("direction") or []:
                A("| %d | direction | `%s` | `%s` | `%s` | %s |"
                  % (t["turn"], x["path"], x["want"], x["got"], "✅" if x["ok"] else "🔴"))
        A("")
        A("**第 4 轮是本脚本最重要的一轮**(负向测试:玩家站在原地什么也没做 -> delta 应为空)。")
        t4 = [t for t in settle_turns if t["turn"] == 4]
        if t4:
            d4 = t4[0].get("delta") or {}
            A("")
            A("> 实测第 4 轮 delta = `%s` -> %s"
              % (json.dumps(d4, ensure_ascii=False),
                 "✅ 空,没有无中生有" if not d4 else "🔴 **凭空产生了 %d 项变化**" % len(d4)))
        A("")

    # ---------------- 对照组 ----------------
    V = report.get("variants") or {}
    if V:
        A("## 4. 对照组 · D3 / D5 / D7")
        A("")
        A("> 三组用的是**同一批正文 + 同一批输入快照**(取自主会话记录的 `snapshot_in`),")
        A("> 每组只改一个变量,所以差异可以归因。")
        A("")
        A("| 组 | 变量 | 结算模型 | 规则 | delta 语义 | 可解析率 | 直接解析率 | schema 合法率 | 断言通过率 | 违规总数 |")
        A("|---|---|---|---|---|---|---|---|---|---|")
        base = agg(settle_turns)
        if settle_turns:
            A("| **S(基准)** | — | `%s` | STRICT | 绝对值 | %s | %s | %s | %s | %d |"
              % (report.get("model"), pct(base["parsed"], base["n"]),
                 pct(base["direct"], base["n"]), pct(base["schema_ok"], base["n"]),
                 pct(base["assert_p"], base["assert_t"]), base["viol"]))
        for name in ("d3", "d5", "d7"):
            v = V.get(name)
            if not v:
                continue
            if v.get("skipped"):
                A("| %s | — | — | — | — | 跳过:%s | | | | |" % (name.upper(), v["skipped"]))
                continue
            gg = agg(v["turns"])
            A("| %s | %s | `%s` | %s | %s | %s | %s | %s | %s | %d |"
              % (name.upper(), v["label"][:18], v["model"], v["rules"],
                 "绝对值" if v["semantics"] == "abs" else "增量",
                 pct(gg["parsed"], gg["n"]), pct(gg["direct"], gg["n"]),
                 pct(gg["schema_ok"], gg["n"]),
                 pct(gg["assert_p"], gg["assert_t"]), gg["viol"]))
        A("")
        for name in ("d3", "d5", "d7"):
            v = V.get(name)
            if not v or v.get("skipped"):
                continue
            A("### 4.%s %s · %s" % ({"d3": "1", "d5": "2", "d7": "3"}[name],
                                     name.upper(), v["label"]))
            A("")
            A("| 轮 | delta | 违规 | 断言 |")
            A("|---|---|---|---|")
            for t in v["turns"]:
                A("| %d | `%s` | %s | %d/%d |"
                  % (t["turn"], json.dumps(t.get("delta") or {}, ensure_ascii=False)[:160],
                     "; ".join("%s %s" % (x.get("code"), x.get("path", ""))
                               for x in (t.get("violations") or [])) or "✅ 0",
                     t.get("assert_passed") or 0, t.get("assert_total") or 0))
            A("")

    # ---------------- D4 成本 ----------------
    C = report.get("cost") or {}
    if C:
        A("## 5. D4 · 成本对照")
        A("")
        A("| 调用类型 | 次数 | prompt | 其中缓存命中 | completion | reasoning | 真实花费 |")
        A("|---|---|---|---|---|---|---|")
        tot_cost, tot_calls = 0.0, 0
        for k, v in C.items():
            tot_cost += v["cost_usd"]
            tot_calls += v["calls"]
            A("| %s | %d | %d | %d | %d | %d | %s |"
              % (k, v["calls"], v["prompt"], v["cached"], v["completion"],
                 v["reasoning"],
                 ("$%.6f" % v["cost_usd"]) if v["cost_known"] else "(该服务商不回 cost)"))
        A("| **合计** | **%d** | | | | | **$%.6f** |" % (tot_calls, tot_cost))
        A("")
        nar = C.get("叙事调用")
        set_ = C.get("结算调用")
        if nar and set_ and nar["calls"] and set_["calls"]:
            A("**方案 C 的成本结构**(每轮 = 1 次叙事 + 1 次结算):")
            A("")
            A("| 指标 | 叙事调用 | 结算调用 | 结算占比 |")
            A("|---|---|---|---|")
            np_, sp = nar["prompt"] / nar["calls"], set_["prompt"] / set_["calls"]
            nc, sc = nar["completion"] / nar["calls"], set_["completion"] / set_["calls"]
            A("| 平均 prompt token | %.0f | %.0f | %.0f%% |"
              % (np_, sp, 100.0 * sp / (np_ + sp) if np_ + sp else 0))
            A("| 平均 completion token | %.0f | %.0f | %.0f%% |"
              % (nc, sc, 100.0 * sc / (nc + sc) if nc + sc else 0))
            A("")
            A("> 结论:方案 C 比单通道贵的那部分 ≈ 上表「结算占比」。")
            A("> 结算调用的 prompt 里不含完整对话历史(只有规则+快照+本轮输入+本轮正文),")
            A("> 所以**会话越长,结算调用的相对成本越低**。")
        A("")

    # ---------------- 产出物 ----------------
    A("## 6. 产出物")
    A("")
    A("| 文件 | 内容 |")
    A("|---|---|")
    A("| `narratives.json` | ⭐ 叙事正文缓存。跑对照组会复用它,**不用再付叙事的钱** |")
    A("| `<provider>/s_t*_narr_raw.txt` | 叙事调用原始 SSE(带毫秒戳) |")
    A("| `<provider>/s_t*_narr_text.txt` | 拼好的正文 |")
    A("| `<provider>/*_settle_raw.json` | 每次结算调用的原始响应 |")
    A("| `raw_report4.json` | 本报告全部原始数据(含每轮完整状态快照) |")
    return "\n".join(L) + "\n"


# ==========================================================================
# 10. main
# ==========================================================================
def print_cost_estimate(rounds, steps):
    narr = rounds if "s" in steps else 0
    settle = (rounds if "s" in steps else 0) + \
             sum(rounds for s in ("d3", "d5", "d7") if s in steps)
    say("")
    say("成本预估(很粗,只为让你有个数):")
    say("   叙事调用 %d 次(不限 max_tokens,约 400 字正文)" % narr)
    say("   结算调用 %d 次(每次 <= %d token)" % (settle, SETTLE_MAX_TOKENS))
    say("   这档价位(glm-5.1 / deepseek-flash)下合计约 $0.01 ~ $0.03")
    say("   你的 OpenRouter credit limit 已设为 $5,跑飞也烧不穿。")
    say("")


def main():
    argv = sys.argv[1:]
    provider = "openrouter"
    rounds = len(FX.SCRIPT)
    only = None
    settle_model = None
    dry = "--dry-run" in argv
    reuse = "--no-reuse" not in argv

    for i, a in enumerate(argv):
        nxt = argv[i + 1] if i + 1 < len(argv) else None
        if a == "--provider" and nxt:
            provider = nxt.strip().lower()
        elif a == "--rounds" and nxt:
            try:
                rounds = max(1, min(len(FX.SCRIPT), int(nxt)))
            except Exception:
                pass
        elif a == "--only" and nxt:
            only = {x.strip().lower() for x in nxt.split(",") if x.strip()}
        elif a == "--settle-model" and nxt:
            settle_model = nxt.strip()

    # d1/d2/d6 都属于主会话,给个别名
    if only:
        if only & {"d1", "d2", "d6", "s"}:
            only = (only - {"d1", "d2", "d6"}) | {"s"}
    steps = only or {"s", "d3", "d5", "d7"}

    say("Mirage · M0 双通道实测(m0_dualchannel.py)")
    say("=" * 62)
    say("验证目标:V-12 —— docs/05 §4.0 的方案 C(叙事/结算双通道)能不能用")
    say("测试材料:真实角色卡 DiLaoJSON.json(E1),%d 轮固定脚本" % rounds)
    _STEP_LABEL = {
        "s": "s = 主会话(一次跑完 D1 正文纯净度 / D2 结算可解析率 / D6 断言)",
        "d3": "d3 = 廉价模型做结算(对照)",
        "d5": "d5 = 严格规则 vs 宽松规则(对照)",
        "d7": "d7 = delta 绝对新值 vs 增量(对照)",
    }
    say("本次要跑:%s" % ", ".join(sorted(steps)))
    for _s in sorted(steps):
        say("   · %s" % _STEP_LABEL.get(_s, _s))
    say("   (D4 成本与结算占比从以上全部调用汇总,无需单独跑)")

    if provider not in PROVIDERS:
        say("🔴 未知服务商 %r,可选:%s" % (provider, ", ".join(PROVIDERS)))
        return
    cfg = PROVIDERS[provider]
    say("服务商:%s   模型:%s" % (provider, cfg["model"]))

    report = {
        "ts": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "provider": provider, "model": cfg["model"], "rounds": rounds,
        "ran": sorted(steps), "step_errors": [],
    }

    # ---------- dry-run:只看装配,不花钱 ----------
    if dry:
        say("")
        say("--dry-run:只打印装配结果,不发任何请求。")
        state = deep_copy(FX.INIT_STATE)

        # 🔴 2026-10-18:原来这里只装配「第 1 轮 + 空 history」,
        # 恰好是唯一不会触发 history 展开 bug 的一条路径,所以 dry-run 形同虚设。
        # 现在必须**走完整个脚本**,每轮都过结构守卫,并打印 messages 条数的增长曲线。
        say("")
        say("【全脚本装配自检】逐轮累积 history,每轮都过发送前结构守卫:")
        _hist = []
        _last_delta = {}
        _counts = []
        for _spec in FX.SCRIPT[:rounds]:
            _m, _g = build_narrative_messages(
                _hist, _spec["input"], _spec.get("worldbook"), _last_delta)
            try:
                assert_messages_wellformed(_m, "dry-run 第 %d 轮叙事" % _spec["turn"])
                _verdict = "✅ 结构合法"
            except Exception as e:
                _verdict = "🔴 " + str(e)[:160]
            _sm = build_settle_messages(state, _spec["input"], "(占位正文)",
                                        FX.SETTLE_RULES_STRICT, "abs")
            try:
                assert_messages_wellformed(_sm, "dry-run 第 %d 轮结算" % _spec["turn"])
                _sverdict = "✅"
            except Exception as e:
                _sverdict = "🔴 " + str(e)[:80]
            _counts.append(len(_m))
            say("   第 %d 轮: 叙事 messages %2d 条 %s | 结算 messages %d 条 %s"
                % (_spec["turn"], len(_m), _verdict, len(_sm), _sverdict))
            # 模拟一轮结束:把本轮循环组 + 一条占位 assistant 累积进 history
            _hist.extend(_g)
            _hist.append({"role": "assistant", "content": "(占位正文)"})
            _last_delta = {}
        say("   messages 条数增长:%s" % " -> ".join(str(c) for c in _counts))
        say("   (每轮应 +4:玩家输入 / 世界书 / 上轮变化 / 上轮 assistant;"
            "若出现 +8 这类翻倍,就是 dict 被当 list 展开了)")

        spec = FX.SCRIPT[0]
        msgs, _ = build_narrative_messages([], spec["input"], spec.get("worldbook"), {})
        say("")
        say("【叙事调用 messages · 第 1 轮明细】共 %d 条:" % len(msgs))
        for j, m in enumerate(msgs):
            say("  [%d] %-9s %d 字  %s" % (j, m["role"], len(m["content"]),
                                           m["content"][:60].replace("\n", " / ")))
        smsgs = build_settle_messages(state, spec["input"], "(占位正文)",
                                      FX.SETTLE_RULES_STRICT, "abs")
        say("")
        say("【结算调用 messages】共 %d 条:" % len(smsgs))
        for j, m in enumerate(smsgs):
            say("  [%d] %-9s %d 字  %s" % (j, m["role"], len(m["content"]),
                                           m["content"][:60].replace("\n", " / ")))
        say("")
        say("稳定前缀合计 %d 字(这部分每轮一字不改,吃前缀缓存)"
            % sum(len(m["content"]) for m in build_stable_prefix()))
        say("变量路径 %d 个,固定脚本 %d 轮,断言 %d 条"
            % (len(FX.VAR_CATALOG), len(FX.SCRIPT),
               sum(len(s.get("must_change") or []) + len(s.get("must_not_change") or [])
                   + len(s.get("direction") or {}) for s in FX.SCRIPT)))
        os.makedirs(OUT_DIR, exist_ok=True)
        with open(os.path.join(OUT_DIR, "dryrun_messages.json"), "w", encoding="utf-8") as f:
            f.write(redact(json.dumps({"narrative": msgs, "settle": smsgs},
                                      ensure_ascii=False, indent=1)))
        say("")
        say("完整装配已写出:%s"
            % os.path.normpath(os.path.join(OUT_DIR, "dryrun_messages.json")))
        return

    raw = os.environ.get(cfg["env"])
    key, msgs_ = validate_key(provider, cfg["env"], raw)
    for m in msgs_:
        say("   [%s] %s" % (provider, m))
    if not key:
        say("🔴 Key 校验未通过,不发任何请求。")
        say("   先 export %s=\"sk-...\"(用英文直双引号)" % cfg["env"])
        return

    print_cost_estimate(rounds, steps)
    say("3 秒后开始,Ctrl-C 可取消…")
    try:
        time.sleep(3)
    except KeyboardInterrupt:
        say("已取消。")
        return

    try:
        if "s" in steps:
            e = run_step("S", run_session, provider, cfg, key, report, rounds, reuse)
            if e:
                report["step_errors"].append(e)
        if "d3" in steps:
            m = settle_model or ("deepseek/deepseek-chat"
                                 if provider == "openrouter" else cfg["model"])
            e = run_step("D3", run_variant, provider, cfg, key, report, rounds, "d3",
                         "换更便宜的模型做结算:%s" % m,
                         FX.SETTLE_RULES_STRICT, "abs", m)
            if e:
                report["step_errors"].append(e)
        if "d5" in steps:
            e = run_step("D5", run_variant, provider, cfg, key, report, rounds, "d5",
                         "松规则对照(删掉「正文优先」与「不得凭空编造」)",
                         FX.SETTLE_RULES_LOOSE, "abs", None)
            if e:
                report["step_errors"].append(e)
        if "d7" in steps:
            e = run_step("D7", run_variant, provider, cfg, key, report, rounds, "d7",
                         "语义对照:delta 写增量而不是绝对新值",
                         FX.SETTLE_RULES_STRICT, "inc", None)
            if e:
                report["step_errors"].append(e)
    finally:
        os.makedirs(OUT_DIR, exist_ok=True)
        # ⚠️ --only 模式不覆盖主报告(docs/14 §13.7 记过的坑)
        if only:
            fname = "REPORT4_%s.md" % "_".join(sorted(steps))
        else:
            fname = "REPORT4.md"
        path = os.path.join(OUT_DIR, fname)
        try:
            content = render_report(report)
        except Exception as e:
            import traceback
            traceback.print_exc()
            content = ("# 报告渲染失败\n\n`%r`\n\n原始数据见 `raw_report4.json`,"
                       "数据本身没丢。\n" % (e,))
        with open(path, "w", encoding="utf-8") as f:
            f.write(redact(content))
        with open(os.path.join(OUT_DIR, "raw_report4.json"), "w", encoding="utf-8") as f:
            f.write(redact(json.dumps(report, ensure_ascii=False, indent=1, default=str)))
        say("")
        say("=" * 62)
        if report.get("step_errors"):
            say("⚠️ 有 %d 项异常(详情见报告顶部),其余结果仍有效。"
                % len(report["step_errors"]))
        say("✅ 报告已写出:%s" % os.path.normpath(path))
        say("   把这份 %s 发我即可。" % fname)


if __name__ == "__main__":
    main()
