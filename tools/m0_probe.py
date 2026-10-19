#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Mirage · M0 协议探针 (m0_probe.py)

一次性补齐 docs/14 §7 列出的协议缺口:
  V-8  : 关闭思维链到底省不省钱(reasoning enabled vs disabled 的 usage 对比)
  V-9  : 🔴 SSE 的结束标志到底是什么(是否存在 data: [DONE])
  V-11 : 401 的响应体长什么样
  V-13 : 流式中途断开,服务端什么反应
  V-14 : 是否存在 keep-alive / 注释心跳行
  V-15 : 首 chunk 延迟

设计原则(对应 docs/07 的六条铁律):
  * API Key **只从环境变量读**,绝不写进本文件
  * 产出物**全部过脱敏**,Authorization 整行不落盘
  * 只用 Python 标准库,不需要 pip install 任何东西

用法:
    export MIRAGE_DS_KEY="sk-..."          # DeepSeek,可选
    export MIRAGE_OR_KEY="sk-or-v1-..."    # OpenRouter,可选
    python3 tools/m0_probe.py              # 两个都跑(有哪个跑哪个)
    python3 tools/m0_probe.py deepseek     # 只跑一家

产出:
    out/<provider>/*.txt     原始响应与原始 SSE 流(已脱敏)
    out/REPORT.md            汇总报告 —— 这份可以直接发给我
"""

import json
import os
import re
import socket
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "out")

# --------------------------------------------------------------------------
# 服务商配置
# --------------------------------------------------------------------------
PROVIDERS = {
    "deepseek": {
        "env": "MIRAGE_DS_KEY",
        "url": "https://api.deepseek.com/chat/completions",
        "model": os.environ.get("MIRAGE_DS_MODEL", "deepseek-chat"),
        # DeepSeek 方言:用 thinking 开关思维链
        "reasoning_on": {"thinking": {"type": "enabled"}},
        "reasoning_off": {"thinking": {"type": "disabled"}},
        "extra": {},
    },
    "openrouter": {
        "env": "MIRAGE_OR_KEY",
        "url": "https://openrouter.ai/api/v1/chat/completions",
        "model": os.environ.get("MIRAGE_OR_MODEL", "z-ai/glm-5.1"),
        # OpenRouter 方言:用 reasoning 开关;注意 exclude 与 enabled 不是一回事(V-8 要验的就是这个)
        "reasoning_on": {"reasoning": {"enabled": True}},
        "reasoning_off": {"reasoning": {"enabled": False}},
        "extra": {},
    },
}

PROMPT = "用大约 150 字描写一座雨中的废弃车站。"

# --------------------------------------------------------------------------
# 脱敏:产出物里绝不能出现 Key
# --------------------------------------------------------------------------
_SECRET_PATTERNS = [
    re.compile(r"(sk-or-v1-[A-Za-z0-9]{4})[A-Za-z0-9]+"),
    re.compile(r"(sk-ant-[A-Za-z0-9_\-]{4})[A-Za-z0-9_\-]+"),
    re.compile(r"(sk-[A-Za-z0-9]{4})[A-Za-z0-9]{8,}"),
    re.compile(r"(AIza[A-Za-z0-9_\-]{4})[A-Za-z0-9_\-]+"),
]


def redact(text):
    """把任何像 Key 的东西打码。宁可误伤,不可漏网。"""
    if not isinstance(text, str):
        text = str(text)
    for pat in _SECRET_PATTERNS:
        text = pat.sub(r"\1***REDACTED***", text)
    # 保险:整行干掉真正的 Authorization 头(形如 `Authorization: Bearer xxx` 或 `-H "Authorization: ..."`)
    # 注意:不能用 `^.*authorization.*$`,那会把正文里提到 "Authorization" 的说明文字也一起吃掉
    text = re.sub(
        r'(?im)^\s*(?:-H\s+)?["\']?authorization["\']?\s*:\s*.*$',
        "<Authorization header omitted>",
        text,
    )
    return text


# --------------------------------------------------------------------------
# Key 净化与校验
#
# 为什么需要这一段(2026-10-16 实测教训,docs/14 §9):
#   第一次跑探针时 7 项全部失败,状态码 -1,报的是
#       UnicodeEncodeError('latin-1', 'Bearer “sk-...”', 7, 8, ...)
#   根因:`export KEY=“sk-...”` 用的是**中文智能引号 U+201C/U+201D**。
#   shell 不认它是引号,于是把它当成了 Key 的一部分;
#   而 HTTP 头**必须 latin-1 可编码**,于是请求根本没发出去。
#   → 这类"用户粘贴进来的脏字符"在产品里一定会遇到,必须在入口就净化 + 校验。
# --------------------------------------------------------------------------

# 成对的"引号"——包括各种中日韩全角引号
_QUOTE_PAIRS = [
    ('"', '"'), ("'", "'"),
    ("\u201c", "\u201d"),  # “ ”  中文双引号
    ("\u2018", "\u2019"),  # ‘ ’  中文单引号
    ("\u300c", "\u300d"),  # 「 」
    ("\u300e", "\u300f"),  # 『 』
    ("\uff02", "\uff02"),  # ＂ 全角引号
]

# 不可见 / 零宽字符:从网页复制 Key 时的经典污染源
_INVISIBLE = dict.fromkeys(
    [0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0x00A0, 0x3000], None
)


def sanitize_key(raw):
    """
    返回 (clean_key, notes)。notes 记录做了哪些清理,便于告知用户。
    """
    notes = []
    k = raw

    if k != k.strip():
        notes.append("去掉了首尾空白/换行")
    k = k.strip()

    before = k
    k = k.translate(_INVISIBLE)
    if k != before:
        notes.append("去掉了零宽/不可见字符(典型来自网页复制)")

    # 反复剥掉成对引号(可能嵌套,如 "“sk-...”")
    for _ in range(4):
        stripped = False
        for lq, rq in _QUOTE_PAIRS:
            if len(k) >= 2 and k.startswith(lq) and k.endswith(rq):
                k = k[1:-1].strip()
                notes.append("剥掉了包裹 Key 的引号 %s%s" % (lq, rq))
                stripped = True
                break
        if not stripped:
            break

    # 落单的引号(只有一边)
    quote_chars = "".join(set("".join(a + b for a, b in _QUOTE_PAIRS)))
    before = k
    k = k.strip(quote_chars).strip()
    if k != before:
        notes.append("去掉了落单的引号字符")

    return k, notes


def validate_key(name, env_name, raw):
    """
    返回 (clean_key_or_None, messages)。
    校验不通过时返回 None —— **宁可不发请求,也不要烧 7 次调用去撞同一面墙**。
    """
    msgs = []
    if not raw:
        return None, ["环境变量 %s 未设置" % env_name]

    key, notes = sanitize_key(raw)
    for n in notes:
        msgs.append("已自动修正:%s" % n)

    if not key:
        return None, msgs + ["清理后 Key 为空 —— 环境变量里大概只有引号"]

    # HTTP 头必须 latin-1 可编码。这是第一轮 7 项全挂的直接原因。
    header_value = "Bearer " + key
    try:
        header_value.encode("latin-1")
    except UnicodeEncodeError as e:
        bad = header_value[e.start:e.end] or "?"
        # e.start 是 header_value 上的下标,换算回 Key 内的位置(减去 "Bearer " 的 7 个字符)
        pos_in_key = max(0, e.start - len("Bearer "))
        return None, msgs + [
            "Key 的第 %d 个字符是非 ASCII 字符 %r (U+%04X),HTTP 头无法编码。"
            % (pos_in_key + 1, bad, ord(bad[0])),
            "最常见原因:`export %s=\u201csk-...\u201d` 用了**中文输入法的智能引号**。" % env_name,
            '正确写法:切英文输入法,用普通双引号 → export %s="sk-..."' % env_name,
        ]

    if any(c.isspace() for c in key):
        return None, msgs + ["Key 中间含有空格/制表符,请检查是否复制多了内容"]

    if not key.startswith("sk-") and not key.startswith("AIza"):
        msgs.append("⚠️ 前缀不是 sk-/AIza,可能不是一个有效 Key(仍会尝试)")

    if len(key) < 20:
        msgs.append("⚠️ 长度只有 %d,偏短,可能被截断了(仍会尝试)" % len(key))

    # 指纹:让你能肉眼确认"这是我想用的那把 Key",同时不泄露内容
    msgs.append("指纹:%s… 共 %d 字符" % (key[:7], len(key)))
    return key, msgs


def write_out(provider, name, content):
    d = os.path.join(OUT_DIR, provider)
    os.makedirs(d, exist_ok=True)
    path = os.path.join(d, name)
    with open(path, "w", encoding="utf-8") as f:
        f.write(redact(content))
    return path


# --------------------------------------------------------------------------
# HTTP
# --------------------------------------------------------------------------
def build_request(cfg, key, body, stream):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(cfg["url"], data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", "Bearer " + key)
    if stream:
        req.add_header("Accept", "text/event-stream")
    if "openrouter" in cfg["url"]:
        # OpenRouter 的署名头,可选
        req.add_header("X-Title", "Mirage-M0-Probe")
    return req


def post_json(cfg, key, body, timeout=180):
    """非流式。返回 (http_status, headers, body_text, elapsed_sec)"""
    req = build_request(cfg, key, body, stream=False)
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, dict(resp.headers), raw, time.time() - t0
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        return e.code, dict(e.headers), raw, time.time() - t0
    except Exception as e:
        return -1, {}, "EXCEPTION: %r" % (e,), time.time() - t0


def post_sse(cfg, key, body, timeout=180, abort_after_lines=None):
    """
    流式。逐行读取并记录时间戳。
    abort_after_lines: 读到第 N 个 data 行就强行关闭连接(用于 V-13 中断测试)
    返回 dict
    """
    req = build_request(cfg, key, body, stream=True)
    result = {
        "status": None,
        "headers": {},
        "lines": [],            # [(elapsed_ms, raw_line)]
        "first_data_ms": None,
        "data_line_count": 0,
        "blank_line_count": 0,
        "comment_line_count": 0,
        "saw_done_sentinel": False,
        "done_sentinel_text": None,
        "last_5_lines": [],
        "aborted": False,
        "error": None,
        "total_ms": None,
    }
    t0 = time.time()
    resp = None
    try:
        resp = urllib.request.urlopen(req, timeout=timeout)
        result["status"] = resp.status
        result["headers"] = dict(resp.headers)
        for raw in resp:
            line = raw.decode("utf-8", "replace").rstrip("\r\n")
            ms = int((time.time() - t0) * 1000)
            result["lines"].append((ms, line))

            if line == "":
                result["blank_line_count"] += 1
            elif line.startswith(":"):
                result["comment_line_count"] += 1
            elif line.startswith("data:"):
                result["data_line_count"] += 1
                if result["first_data_ms"] is None:
                    result["first_data_ms"] = ms
                payload = line[5:].strip()
                # V-9 的核心:结束哨兵
                if payload.startswith("[") and payload.endswith("]"):
                    result["saw_done_sentinel"] = True
                    result["done_sentinel_text"] = payload
                if abort_after_lines and result["data_line_count"] >= abort_after_lines:
                    result["aborted"] = True
                    break
    except urllib.error.HTTPError as e:
        result["status"] = e.code
        result["headers"] = dict(e.headers)
        result["error"] = e.read().decode("utf-8", "replace")
    except Exception as e:
        result["error"] = "EXCEPTION: %r" % (e,)
    finally:
        if resp is not None:
            try:
                resp.close()
            except Exception:
                pass

    result["total_ms"] = int((time.time() - t0) * 1000)
    result["last_5_lines"] = [l for _, l in result["lines"][-5:]]
    return result


# --------------------------------------------------------------------------
# 解析辅助
# --------------------------------------------------------------------------
def extract_usage(raw_text):
    """从非流式响应或 SSE 流里尽量抠出最后一个 usage 对象"""
    best = None
    for m in re.finditer(r'"usage"\s*:\s*', raw_text):
        start = m.end()
        depth, i = 0, start
        while i < len(raw_text):
            if raw_text[i] == "{":
                depth += 1
            elif raw_text[i] == "}":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        try:
            obj = json.loads(raw_text[start:i + 1])
            if isinstance(obj, dict) and obj:
                best = obj
        except Exception:
            pass
    return best


def summarize_usage(u):
    if not u:
        return "(无 usage)"
    ctd = u.get("completion_tokens_details") or {}
    ptd = u.get("prompt_tokens_details") or {}
    parts = [
        "prompt=%s" % u.get("prompt_tokens"),
        "completion=%s" % u.get("completion_tokens"),
        "reasoning=%s" % ctd.get("reasoning_tokens"),
        "cached=%s" % (ptd.get("cached_tokens") if "cached_tokens" in ptd
                       else u.get("prompt_cache_hit_tokens")),
    ]
    if u.get("cost") is not None:
        parts.append("cost=$%s" % u.get("cost"))
    return ", ".join(parts)


def base_body(cfg, stream, reasoning_mode=None, max_tokens=None):
    body = {
        "model": cfg["model"],
        "messages": [{"role": "user", "content": PROMPT}],
        "stream": stream,
    }
    if stream:
        body["stream_options"] = {"include_usage": True}
    if max_tokens is not None:
        body["max_tokens"] = max_tokens
    if reasoning_mode == "on":
        body.update(cfg["reasoning_on"])
    elif reasoning_mode == "off":
        body.update(cfg["reasoning_off"])
    body.update(cfg.get("extra") or {})
    return body


# --------------------------------------------------------------------------
# 各项探测
# --------------------------------------------------------------------------
def probe(provider, cfg, key, report):
    say = lambda s: print("  " + s, flush=True)
    R = report.setdefault(provider, {})

    # ---- 1. 连通性(非流式) -------------------------------------------
    say("[1/7] 非流式连通性 …")
    st, hdr, raw, el = post_json(cfg, key, base_body(cfg, stream=False))
    write_out(provider, "01_nonstream.json", raw)
    R["nonstream"] = {
        "status": st,
        "elapsed_s": round(el, 2),
        "usage": extract_usage(raw),
        "content_type": hdr.get("Content-Type"),
    }
    say("     HTTP %s · %.1fs · %s" % (st, el, summarize_usage(R["nonstream"]["usage"])))
    if st != 200:
        say("     ⚠️ 非 200。响应已存 01_nonstream.json")
        # 连最基本的一次请求都没成功 → 后面 9 次只会撞同一面墙,直接中止这家
        # (第一轮就是因为没有这个闸门,白跑了 7 项全是 -1)
        R["aborted_early"] = True
        R["abort_reason"] = redact(raw)[:300]
        say("     ⛔ 跳过该服务商剩余探测,避免重复失败。")
        say("        先解决这个错误再重跑:%s" % redact(raw)[:160])
        return

    # ---- 2. V-11 · 错误 Key 的 401 -------------------------------------
    say("[2/7] V-11 · 故意用错误 Key,采集 401 响应体 …")
    bad = key[:-4] + "0000" if len(key) > 8 else "sk-invalid"
    st, hdr, raw, el = post_json(cfg, bad, base_body(cfg, stream=False), timeout=60)
    write_out(provider, "02_badkey.json", "HTTP %s\n\n%s" % (st, raw))
    R["badkey"] = {"status": st, "body": redact(raw)[:800]}
    say("     HTTP %s" % st)

    # ---- 3. V-9/V-14/V-15 · 流式原始流 ---------------------------------
    say("[3/7] V-9/V-14/V-15 · 流式原始流(关键:看结束标志) …")
    s = post_sse(cfg, key, base_body(cfg, stream=True))
    dump = "\n".join("[%6dms] %s" % (ms, ln) for ms, ln in s["lines"])
    write_out(provider, "03_stream_raw.txt", dump)
    R["stream"] = {
        "status": s["status"],
        "first_data_ms": s["first_data_ms"],
        "total_ms": s["total_ms"],
        "data_lines": s["data_line_count"],
        "blank_lines": s["blank_line_count"],
        "comment_lines": s["comment_line_count"],
        "saw_done_sentinel": s["saw_done_sentinel"],
        "done_sentinel_text": s["done_sentinel_text"],
        "last_5_lines": [redact(l)[:160] for l in s["last_5_lines"]],
        "usage": extract_usage(dump),
        "error": redact(s["error"])[:500] if s["error"] else None,
    }
    say("     首 chunk %sms · 共 %s 个 data 行 · 注释行 %s"
        % (s["first_data_ms"], s["data_line_count"], s["comment_line_count"]))
    say("     结束哨兵: %s" % (s["done_sentinel_text"] if s["saw_done_sentinel"]
                              else "❌ 未出现 —— 直接断流"))

    # ---- 4. V-13 · 中途中断 --------------------------------------------
    say("[4/7] V-13 · 读 5 个 chunk 后强行断开 …")
    s2 = post_sse(cfg, key, base_body(cfg, stream=True), abort_after_lines=5)
    write_out(provider, "04_abort.txt",
              "\n".join("[%6dms] %s" % (ms, ln) for ms, ln in s2["lines"]))
    R["abort"] = {"aborted": s2["aborted"], "data_lines": s2["data_line_count"],
                  "total_ms": s2["total_ms"]}
    say("     已断开(%s 行,%sms)。⚠️ 是否继续计费请到平台后台用量页核对"
        % (s2["data_line_count"], s2["total_ms"]))

    # ---- 5. V-8 · 关思考 vs 开思考 -------------------------------------
    say("[5/7] V-8 · 思维链 开/关 的 usage 与成本对比 …")
    cmp_res = {}
    for mode in ("on", "off"):
        st, hdr, raw, el = post_json(cfg, key, base_body(cfg, stream=False, reasoning_mode=mode))
        write_out(provider, "05_reasoning_%s.json" % mode, raw)
        u = extract_usage(raw)
        cmp_res[mode] = {"status": st, "elapsed_s": round(el, 2), "usage": u}
        say("     reasoning=%-3s → HTTP %s · %.1fs · %s" % (mode, st, el, summarize_usage(u)))
    R["reasoning_cmp"] = cmp_res

    # ---- 6. R-T15 复现 · 小 max_tokens 会不会把正文饿死 -----------------
    say("[6/7] R-T15 复现 · max_tokens=256 + 开思考,看 content 是否为空 …")
    st, hdr, raw, el = post_json(
        cfg, key, base_body(cfg, stream=False, reasoning_mode="on", max_tokens=256))
    write_out(provider, "06_starved.json", raw)
    content, finish = None, None
    try:
        j = json.loads(raw)
        ch = (j.get("choices") or [{}])[0]
        content = (ch.get("message") or {}).get("content")
        finish = ch.get("finish_reason")
    except Exception:
        pass
    R["starved"] = {
        "status": st,
        "finish_reason": finish,
        "content_is_empty": (content is None or content == ""),
        "content_preview": (content or "")[:80],
        "usage": extract_usage(raw),
    }
    say("     finish_reason=%s · content 为空? %s"
        % (finish, R["starved"]["content_is_empty"]))

    # ---- 7. 模型列表(A-4 可行性 / V-10) --------------------------------
    say("[7/7] V-10 · GET 模型列表(验证 A-4「自动拉模型」能不能做) …")
    models_url = cfg["url"].replace("/chat/completions", "/models")
    try:
        req = urllib.request.Request(models_url)
        req.add_header("Authorization", "Bearer " + key)
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read().decode("utf-8", "replace")
            st = resp.status
    except urllib.error.HTTPError as e:
        raw, st = e.read().decode("utf-8", "replace"), e.code
    except Exception as e:
        raw, st = "EXCEPTION: %r" % (e,), -1
    write_out(provider, "07_models.json", raw)
    n = raw.count('"id"')
    R["models"] = {"status": st, "url": models_url, "approx_model_count": n}
    say("     HTTP %s · 约 %s 个模型条目" % (st, n))


# --------------------------------------------------------------------------
# 报告
# --------------------------------------------------------------------------
def render_report(report):
    L = []
    A = L.append
    A("# Mirage · M0 协议探针报告")
    A("")
    A("| 项 | 值 |")
    A("|---|---|")
    A("| 生成时间 | %s |" % datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"))
    A("| 探测服务商 | %s |" % ", ".join(report.keys()))
    A("| 脱敏 | ✅ 所有产出物已过 `redact()`,Authorization 整行移除 |")
    A("")
    A("> 这份报告与 `out/<provider>/` 下的原始文件可以直接发给我。")
    A("")

    # 失败的服务商单独置顶,避免淹没在一堆 None 里
    failed = {p: r for p, r in report.items() if r.get("aborted_early") or r.get("fatal")}
    if failed:
        A("## ⛔ 下列服务商在第一步就失败了,后续探测已跳过")
        A("")
        A("| 服务商 | 原因 |")
        A("|---|---|")
        for p, r in failed.items():
            reason = (r.get("abort_reason") or r.get("fatal") or "").replace("\n", " ")
            A("| %s | `%s` |" % (p, reason[:220]))
        A("")
        A("**先解决上面的错误再重跑。** 本报告其余部分对这些服务商无意义。")
        A("")

    # V-9
    A("## 🔴 V-9 · SSE 结束标志(最关键)")
    A("")
    A("| 服务商 | 有结束哨兵? | 哨兵内容 | data 行数 | 注释/心跳行 | 空行 |")
    A("|---|---|---|---|---|---|")
    for p, r in report.items():
        s = r.get("stream") or {}
        A("| %s | %s | `%s` | %s | %s | %s |" % (
            p,
            "✅ 是" if s.get("saw_done_sentinel") else "❌ 否(直接断流)",
            s.get("done_sentinel_text") or "-",
            s.get("data_lines"), s.get("comment_lines"), s.get("blank_lines")))
    A("")
    for p, r in report.items():
        s = r.get("stream") or {}
        A("**%s 的最后 5 行:**" % p)
        A("")
        A("```")
        for l in (s.get("last_5_lines") or []):
            A(l)
        A("```")
        A("")

    # V-15
    A("## V-15 · 首 chunk 延迟与总耗时")
    A("")
    A("| 服务商 | 首 chunk | 流总耗时 | 非流式总耗时 |")
    A("|---|---|---|---|")
    for p, r in report.items():
        s, n = r.get("stream") or {}, r.get("nonstream") or {}
        A("| %s | %s ms | %s ms | %s s |" % (p, s.get("first_data_ms"),
                                             s.get("total_ms"), n.get("elapsed_s")))
    A("")

    # V-11
    A("## V-11 · 错误 Key 的响应体(C-10 错误文案依据)")
    A("")
    for p, r in report.items():
        b = r.get("badkey") or {}
        A("**%s** — HTTP `%s`" % (p, b.get("status")))
        A("")
        A("```json")
        A((b.get("body") or "").strip())
        A("```")
        A("")

    # V-8
    A("## V-8 · 思维链 开 vs 关")
    A("")
    A("| 服务商 | 模式 | HTTP | 耗时 | usage |")
    A("|---|---|---|---|---|")
    for p, r in report.items():
        for mode, d in (r.get("reasoning_cmp") or {}).items():
            A("| %s | %s | %s | %ss | %s |" % (p, mode, d.get("status"),
                                               d.get("elapsed_s"), summarize_usage(d.get("usage"))))
    A("")
    A("> **怎么看:** 如果 `off` 那行的 `reasoning=0` 或 `reasoning=None` 且 `completion` 明显变小,")
    A("> 说明**真的关掉了、真的省钱**;如果 `reasoning` 依然有值,说明这家只是\"不返回\"而**照样计费**。")
    A("")

    # R-T15
    A("## R-T15 复现 · max_tokens 饿死正文")
    A("")
    A("| 服务商 | finish_reason | content 为空? | content 预览 | usage |")
    A("|---|---|---|---|---|")
    for p, r in report.items():
        s = r.get("starved") or {}
        A("| %s | `%s` | %s | %s | %s |" % (
            p, s.get("finish_reason"),
            "🔴 是" if s.get("content_is_empty") else "否",
            (s.get("content_preview") or "").replace("|", "\\|").replace("\n", " "),
            summarize_usage(s.get("usage"))))
    A("")

    # V-13
    A("## V-13 · 中途断开")
    A("")
    A("| 服务商 | 已读 data 行 | 断开耗时 |")
    A("|---|---|---|")
    for p, r in report.items():
        a = r.get("abort") or {}
        A("| %s | %s | %s ms |" % (p, a.get("data_lines"), a.get("total_ms")))
    A("")
    A("> ⚠️ **是否继续计费,脚本测不出来。** 请在跑完后去平台的 用量/Activity 页面,")
    A("> 看这次被中断的请求记了多少 completion token —— 如果接近完整回复,说明**中断不省钱**。")
    A("")

    # V-10
    A("## V-10 · 模型列表(A-4 可行性)")
    A("")
    A("| 服务商 | 端点 | HTTP | 大致条目数 |")
    A("|---|---|---|---|")
    for p, r in report.items():
        m = r.get("models") or {}
        A("| %s | `%s` | %s | %s |" % (p, m.get("url"), m.get("status"), m.get("approx_model_count")))
    A("")

    A("---")
    A("")
    A("## 原始文件索引")
    A("")
    A("| 文件 | 内容 |")
    A("|---|---|")
    A("| `01_nonstream.json` | 非流式完整响应 |")
    A("| `02_badkey.json` | 错误 Key 的响应(V-11) |")
    A("| `03_stream_raw.txt` | ⭐ **带毫秒时间戳的完整原始 SSE 流**(V-9/V-14/V-15) |")
    A("| `04_abort.txt` | 中断测试(V-13) |")
    A("| `05_reasoning_on/off.json` | 思维链开关对比(V-8) |")
    A("| `06_starved.json` | max_tokens=256 饿死测试(R-T15) |")
    A("| `07_models.json` | 模型列表(V-10 / A-4) |")
    return "\n".join(L) + "\n"


# --------------------------------------------------------------------------
def main():
    targets = sys.argv[1:] or list(PROVIDERS.keys())
    report = {}
    ran = []

    print("Mirage · M0 协议探针")
    print("=" * 60)

    # ---- 预检:先把 Key 全部校验一遍,再发任何请求 --------------------
    # 教训:第一轮因为智能引号,7 项探测全部撞同一面墙才发现(docs/14 §9)
    checked = []
    print("\n【预检】")
    for name in targets:
        cfg = PROVIDERS.get(name)
        if not cfg:
            print("  x %-11s 未知服务商 (可选: %s)" % (name, ", ".join(PROVIDERS)))
            continue
        raw = os.environ.get(cfg["env"], "")
        key, msgs = validate_key(name, cfg["env"], raw)
        print("  %s %-11s" % ("OK " if key else "x  ", name))
        for m in msgs:
            print("      - %s" % m)
        if key:
            checked.append((name, cfg, key))

    if not checked:
        print("\n没有可探测的服务商,**未发出任何请求**(所以没有产生任何费用)。")
        print("\n请这样设置环境变量 —— 注意用【英文输入法的普通双引号】:")
        for n, c in PROVIDERS.items():
            print('  export %s="你的 %s key"' % (c["env"], n))
        print("\n设完先自检:")
        env0 = list(PROVIDERS.values())[0]["env"]
        print('  python3 -c "import os;k=os.environ[\'%s\'];print(repr(k[:8]),len(k))"' % env0)
        print("  -> 若输出里出现 \\u201c 或 \u201c,说明引号被当成 Key 的一部分了。")
        return 1

    print("\n【开始探测】每家约 10 次请求,预计花费 < ¥0.1")

    for name, cfg, key in checked:
        print("\n▶ %s  model=%s" % (name, cfg["model"]))
        try:
            probe(name, cfg, key, report)
            ran.append(name)
        except KeyboardInterrupt:
            print("  已中断")
            break
        except Exception as e:
            print("  !! 探测异常: %r" % (e,))
            report.setdefault(name, {})["fatal"] = repr(e)

    if not report:
        print("\n没有产生任何结果。")
        return 1

    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, "REPORT.md")
    with open(path, "w", encoding="utf-8") as f:
        f.write(redact(render_report(report)))

    print("\n" + "=" * 60)
    print("完成。产出目录: %s" % os.path.normpath(OUT_DIR))
    print("  → REPORT.md 是汇总报告,可直接发给我")
    print("  → 已全部脱敏,但发之前建议还是 grep 一下: grep -r 'sk-' out/ | grep -v REDACTED")
    return 0


if __name__ == "__main__":
    socket.setdefaulttimeout(180)
    sys.exit(main())
