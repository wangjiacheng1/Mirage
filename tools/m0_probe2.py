#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Mirage · M0 协议探针 #2 (m0_probe2.py)

第一支探针(m0_probe.py)已全绿,结论见 docs/14 §10。
这一支专门解决它暴露出来的新问题,并**解除关键路径上的阻塞**:

  V-17 🔴 OpenRouter 的 provider 锁定到底生效不生效
          —— 这是 V-12(M0 核心闸门)的前置。不锁 provider,Step 4 的结论就是废的。
             实测依据:60 秒内同一个 model id 命中了 4 个不同上游(docs/14 §10.4)
  V-12a   stream + tools 冒烟测试(DeepSeek 单一上游 + OpenRouter 锁定上游)
          —— 不是完整的 Step 4,是先回答"这个组合压根能不能用"
  V-12b   从本地已下载的 07_models.json 预筛"声明支持 tools"的模型清单(零成本、不发请求)
  V-16    DeepSeek 不传思考参数时,默认到底思不思考
          —— /models 声明 effort.default_level = "high",但实测没返回 reasoning_content,矛盾
  V-18    DeepSeek 流式下思考字段叫什么(reasoning_content? reasoning?)
          —— 第一轮的流式测试没开思考,没取到样本
  V-19    DeepSeek effort 三档(low/high/max)的 token 与耗时差异

设计原则(与 m0_probe.py 一致):
  * API Key 只从环境变量读,绝不写进本文件
  * 产出物全部过脱敏
  * 只用标准库
  * **省钱**:除了必须长输出的用例,prompt 一律极短,且全程显式关思考
    (docs/14 §10.3:不传思考参数贵 7.6 倍)

用法:
    export MIRAGE_DS_KEY="sk-..."           # DeepSeek
    export MIRAGE_OR_KEY="sk-or-v1-..."     # OpenRouter
    python3 tools/m0_probe2.py              # 全跑
    python3 tools/m0_probe2.py --no-net     # 只跑 V-12b(零成本,不发任何请求)

产出:
    out2/<provider>/*.json|txt    原始响应(已脱敏)
    out2/REPORT2.md               汇总报告 —— 这份直接发我
"""

import json
import os
import re
import sys
import time
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

# 复用第一支探针的基础设施(脱敏 / Key 净化 / HTTP)
from m0_probe import (  # noqa: E402
    PROVIDERS,
    redact,
    validate_key,
    post_json,
    post_sse,
    extract_usage,
)

OUT_DIR = os.path.join(HERE, "..", "out2")
MODELS_CACHE = os.path.join(HERE, "..", "out", "openrouter", "07_models.json")

# 极短 prompt:V-17 要跑 6 次,必须便宜
TINY = "只回复两个字:收到"
# 工具调用用的场景 prompt
TOOL_PROMPT = (
    "你是地牢 GM。玩家 HP 100,背包为空。\n"
    "玩家说:我推开石门走进去,被飞镖擦伤了手臂,并捡到一把铜钥匙。\n"
    "请先用 1~2 句话描写场景,然后调用 update_state 工具记录数值变化。"
)
TOOLS_SCHEMA = [{
    "type": "function",
    "function": {
        "name": "update_state",
        "description": "记录本回合玩家状态的增量变化(delta),不是最终值",
        "parameters": {
            "type": "object",
            "properties": {
                "hp_delta": {"type": "integer", "description": "HP 变化量,受伤为负"},
                "items_added": {"type": "array", "items": {"type": "string"}},
                "items_removed": {"type": "array", "items": {"type": "string"}},
                "flags": {"type": "object", "description": "任意剧情标记 key->value"},
            },
            "required": ["hp_delta"],
        },
    },
}]


def say(msg):
    print(msg, flush=True)


def write_out(provider, name, content):
    d = os.path.join(OUT_DIR, provider)
    os.makedirs(d, exist_ok=True)
    path = os.path.join(d, name)
    with open(path, "w", encoding="utf-8") as f:
        f.write(redact(content if isinstance(content, str) else str(content)))
    return path


def jget(raw):
    try:
        return json.loads(raw)
    except Exception:
        return None


def body_of(cfg, prompt, stream=False, reasoning=None, extra=None):
    """reasoning: None=不传 / 'on' / 'off'"""
    b = {
        "model": cfg["model"],
        "messages": [{"role": "user", "content": prompt}],
        "stream": stream,
    }
    if reasoning == "on":
        b.update(cfg["reasoning_on"])
    elif reasoning == "off":
        b.update(cfg["reasoning_off"])
    if extra:
        b.update(extra)
    return b


# =========================================================================
# V-12b · 零成本:从已下载的模型列表预筛支持 tools 的模型
# =========================================================================
def probe_v12b(report):
    say("\n[V-12b] 从本地 07_models.json 预筛支持 tools 的模型(不发请求)")
    if not os.path.exists(MODELS_CACHE):
        say("     ⚠️ 找不到 %s —— 先跑一次 m0_probe.py 或手动拉 /models" % MODELS_CACHE)
        report["v12b"] = {"error": "models cache not found"}
        return
    try:
        data = json.load(open(MODELS_CACHE, encoding="utf-8"))["data"]
    except Exception as e:
        report["v12b"] = {"error": repr(e)}
        return

    with_tools, without = [], []
    for m in data:
        sp = m.get("supported_parameters") or []
        (with_tools if "tools" in sp else without).append(m)

    def brief(m):
        pr = m.get("pricing") or {}
        return {
            "id": m.get("id"),
            "ctx": (m.get("top_provider") or {}).get("context_length") or m.get("context_length"),
            "prompt_price": pr.get("prompt"),
            "completion_price": pr.get("completion"),
            "has_reasoning": "reasoning" in (m.get("supported_parameters") or []),
        }

    # 便宜且支持 tools 的——这正是我们产品用户最可能用的那一档
    def price(m):
        try:
            return float((m.get("pricing") or {}).get("completion") or 9e9)
        except Exception:
            return 9e9

    cheap = sorted([m for m in with_tools if price(m) > 0], key=price)[:15]
    free = [m for m in with_tools if price(m) == 0]

    report["v12b"] = {
        "total": len(data),
        "with_tools": len(with_tools),
        "without_tools": len(without),
        "cheapest_15_with_tools": [brief(m) for m in cheap],
        "free_with_tools": [m.get("id") for m in free][:20],
        "configured_or_model": PROVIDERS["openrouter"]["model"],
        "configured_or_model_supports_tools": any(
            m.get("id") == PROVIDERS["openrouter"]["model"] and "tools" in (m.get("supported_parameters") or [])
            for m in data
        ),
    }
    write_out("openrouter", "v12b_tools_capable_models.json",
              json.dumps(report["v12b"], ensure_ascii=False, indent=1))
    say("     总 %d 个模型,声明支持 tools 的 %d 个(%.0f%%),免费且支持 tools 的 %d 个"
        % (len(data), len(with_tools), 100.0 * len(with_tools) / max(1, len(data)), len(free)))
    say("     我们配置的 OR 模型 %s 支持 tools? → %s"
        % (report["v12b"]["configured_or_model"],
           "✅ 是" if report["v12b"]["configured_or_model_supports_tools"] else "❌ 否"))


# =========================================================================
# V-17 · 🔴 provider 锁定是否生效
# =========================================================================
def probe_v17(key, report):
    cfg = PROVIDERS["openrouter"]
    say("\n[V-17] 🔴 provider 锁定是否生效(这是 V-12 的前置)")

    rounds = []
    # 第一组:不锁,跑 3 次,看漂不漂
    say("     A 组 · 不锁 provider,3 次:")
    seen = []
    for i in range(3):
        st, _, raw, el = post_json(cfg, key, body_of(cfg, TINY, reasoning="off"))
        d = jget(raw) or {}
        p = d.get("provider")
        u = d.get("usage") or {}
        seen.append(p)
        rounds.append({"group": "unlocked", "i": i + 1, "status": st,
                       "provider": p, "cost": u.get("cost"), "elapsed_s": round(el, 2)})
        say("        #%d → HTTP %s · provider=%s · %.1fs · cost=%s"
            % (i + 1, st, p, el, u.get("cost")))
        write_out("openrouter", "v17_unlocked_%d.json" % (i + 1), raw)

    # 选一个真实出现过的 provider 作为锁定目标(比我凭空猜名字可靠)
    target = next((p for p in seen if p), None)
    report["v17_target"] = target
    if not target:
        say("     ⚠️ A 组没拿到任何 provider 名,跳过 B 组")
        report["v17"] = rounds
        return

    # 第二组:锁定,跑 3 次,看是否全部命中 target
    say("     B 组 · 锁定 provider=%s + allow_fallbacks=false,3 次:" % target)
    lock = {"provider": {"order": [target], "allow_fallbacks": False}}
    for i in range(3):
        st, _, raw, el = post_json(cfg, key, body_of(cfg, TINY, reasoning="off", extra=lock))
        d = jget(raw) or {}
        p = d.get("provider")
        u = d.get("usage") or {}
        err = (d.get("error") or {}) if isinstance(d.get("error"), dict) else {}
        rounds.append({"group": "locked", "i": i + 1, "status": st, "provider": p,
                       "cost": u.get("cost"), "elapsed_s": round(el, 2),
                       "hit_target": (p == target), "error": err.get("message")})
        say("        #%d → HTTP %s · provider=%s · 命中目标? %s · %.1fs"
            % (i + 1, st, p, "✅" if p == target else "❌", el))
        write_out("openrouter", "v17_locked_%d.json" % (i + 1), raw)

    report["v17"] = rounds


# =========================================================================
# V-12a · stream + tools 冒烟
# =========================================================================
def probe_v12a(provider, key, report, extra=None):
    cfg = PROVIDERS[provider]
    say("\n[V-12a] %s · stream + tools(M0 核心闸门的冒烟测试)" % provider)

    res = {}
    # ① 非流式 + tools:先确认 tools 本身能用
    st, _, raw, el = post_json(
        cfg, key, body_of(cfg, TOOL_PROMPT, stream=False, reasoning="off",
                          extra=dict(extra or {}, tools=TOOLS_SCHEMA, tool_choice="auto")))
    write_out(provider, "v12a_nonstream_tools.json", raw)
    d = jget(raw) or {}
    ch = (d.get("choices") or [{}])[0]
    msg = ch.get("message") or {}
    tc = msg.get("tool_calls") or []
    res["nonstream"] = {
        "status": st, "elapsed_s": round(el, 2),
        "finish_reason": ch.get("finish_reason"),
        "native_finish_reason": ch.get("native_finish_reason"),
        "provider": d.get("provider"),
        "tool_call_count": len(tc),
        "tool_name": (tc[0].get("function") or {}).get("name") if tc else None,
        "tool_args_raw": (tc[0].get("function") or {}).get("arguments") if tc else None,
        "content_preview": (msg.get("content") or "")[:120],
        "error": (d.get("error") or {}).get("message") if isinstance(d.get("error"), dict) else None,
    }
    # arguments 是字符串,必须能解析成合法 JSON —— 这是方案 A 的生死线
    res["nonstream"]["args_parse_ok"] = bool(tc) and jget(res["nonstream"]["tool_args_raw"] or "") is not None
    say("     非流式 → HTTP %s · finish=%s · tool_calls=%d · args 可解析? %s"
        % (st, ch.get("finish_reason"), len(tc),
           "✅" if res["nonstream"]["args_parse_ok"] else "❌"))

    # ② 流式 + tools:这才是我怀疑会出问题的组合
    sse = post_sse(cfg, key, body_of(cfg, TOOL_PROMPT, stream=True, reasoning="off",
                                     extra=dict(extra or {}, tools=TOOLS_SCHEMA, tool_choice="auto")))
    lines_txt = "\n".join("[%6dms] %s" % (ms, l) for ms, l in sse["lines"])
    write_out(provider, "v12a_stream_tools_raw.txt", lines_txt)

    # 逐 chunk 分析 tool_calls 是怎么到达的
    frag_count, name_chunks, arg_fragments, idx_seen, finish = 0, [], [], set(), []
    content_chunks = 0
    for _, line in sse["lines"]:
        if not line.startswith("data:"):
            continue
        payload = line[5:].strip()
        if payload == "[DONE]" or not payload.startswith("{"):
            continue
        d = jget(payload)
        if not d:
            continue
        for c in d.get("choices") or []:
            if c.get("finish_reason"):
                finish.append(c["finish_reason"])
            delta = c.get("delta") or {}
            if delta.get("content"):
                content_chunks += 1
            for t in delta.get("tool_calls") or []:
                frag_count += 1
                idx_seen.add(t.get("index"))
                fn = t.get("function") or {}
                if fn.get("name"):
                    name_chunks.append(fn["name"])
                if fn.get("arguments") is not None:
                    arg_fragments.append(fn["arguments"])

    joined = "".join(arg_fragments)
    res["stream"] = {
        "status": sse["status"],
        "error": (redact(sse["error"])[:300] if sse["error"] else None),
        "first_data_ms": sse["first_data_ms"],
        "total_ms": sse["total_ms"],
        "data_lines": sse["data_line_count"],
        "comment_lines": sse["comment_line_count"],
        "saw_done": sse["saw_done_sentinel"],
        "finish_reasons": finish,
        "content_delta_chunks": content_chunks,
        "tool_call_fragments": frag_count,
        "tool_call_indices": sorted(x for x in idx_seen if x is not None),
        "name_appeared_times": len(name_chunks),
        "arg_fragment_count": len(arg_fragments),
        "joined_args": joined[:400],
        "joined_args_parse_ok": jget(joined) is not None if joined else False,
    }
    say("     流式   → HTTP %s · data=%d · 工具片段=%d · arguments 分 %d 片 · 拼接后可解析? %s"
        % (sse["status"], sse["data_line_count"], frag_count, len(arg_fragments),
           "✅" if res["stream"]["joined_args_parse_ok"] else "❌"))
    if finish:
        say("        finish_reason 序列: %s" % finish)

    report.setdefault("v12a", {})[provider] = res


# =========================================================================
# V-16 / V-18 / V-19 · DeepSeek 思考行为
# =========================================================================
def probe_ds_reasoning(key, report):
    cfg = PROVIDERS["deepseek"]

    # V-16:不传思考参数,默认到底思不思考?用 3 个不同难度的 prompt 看
    say("\n[V-16] DeepSeek 不传思考参数时,默认思不思考(/models 声明 default_level=high)")
    cases = [
        ("简单", "只回复两个字:收到"),
        ("叙事", "用大约 80 字描写一座雨中的废弃车站。"),
        ("推理", "一个笼子里有鸡和兔共 12 只,脚共 34 只。鸡兔各几只?只给答案。"),
    ]
    v16 = []
    for tag, pmt in cases:
        st, _, raw, el = post_json(cfg, key, body_of(cfg, pmt))  # reasoning=None → 不传
        d = jget(raw) or {}
        msg = ((d.get("choices") or [{}])[0].get("message") or {})
        u = d.get("usage") or {}
        ctd = u.get("completion_tokens_details") or {}
        v16.append({
            "case": tag, "status": st, "elapsed_s": round(el, 2),
            "has_reasoning_content": bool(msg.get("reasoning_content")),
            "reasoning_tokens": ctd.get("reasoning_tokens"),
            "prompt_tokens": u.get("prompt_tokens"),
            "completion_tokens": u.get("completion_tokens"),
            "cache_hit": u.get("prompt_cache_hit_tokens"),
        })
        say("     %s → reasoning_content? %s · reasoning_tokens=%s · prompt=%s · %.1fs"
            % (tag, "✅ 有" if msg.get("reasoning_content") else "❌ 无",
               ctd.get("reasoning_tokens"), u.get("prompt_tokens"), el))
        write_out("deepseek", "v16_default_%s.json" % tag, raw)
    report["v16"] = v16

    # V-18:流式 + 开思考,delta 里思考字段叫什么
    say("\n[V-18] DeepSeek 流式下的思考字段名")
    sse = post_sse(cfg, key, body_of(cfg, cases[2][1], stream=True, reasoning="on"))
    write_out("deepseek", "v18_stream_thinking_raw.txt",
              "\n".join("[%6dms] %s" % (ms, l) for ms, l in sse["lines"]))
    keys = {}
    for _, line in sse["lines"]:
        if not line.startswith("data:"):
            continue
        payload = line[5:].strip()
        if not payload.startswith("{"):
            continue
        d = jget(payload)
        if not d:
            continue
        for c in d.get("choices") or []:
            for k in (c.get("delta") or {}):
                keys[k] = keys.get(k, 0) + 1
    report["v18"] = {
        "status": sse["status"], "delta_keys": keys,
        "first_data_ms": sse["first_data_ms"], "total_ms": sse["total_ms"],
        "comment_lines": sse["comment_line_count"], "saw_done": sse["saw_done_sentinel"],
    }
    say("     delta 出现过的字段: %s" % (keys or "(无)"))

    # V-19:effort 三档
    say("\n[V-19] DeepSeek effort 三档(low/high/max)")
    v19 = []
    for lvl in ("low", "high", "max"):
        st, _, raw, el = post_json(
            cfg, key, body_of(cfg, cases[2][1], reasoning="on", extra={"effort": lvl}))
        d = jget(raw) or {}
        u = d.get("usage") or {}
        ctd = u.get("completion_tokens_details") or {}
        err = d.get("error")
        v19.append({
            "effort": lvl, "status": st, "elapsed_s": round(el, 2),
            "prompt_tokens": u.get("prompt_tokens"),
            "completion_tokens": u.get("completion_tokens"),
            "reasoning_tokens": ctd.get("reasoning_tokens"),
            "error": (err.get("message") if isinstance(err, dict) else err),
        })
        say("     effort=%-4s → HTTP %s · reasoning=%s · completion=%s · %.1fs%s"
            % (lvl, st, ctd.get("reasoning_tokens"), u.get("completion_tokens"), el,
               "  ⚠️ 参数可能不被接受" if st != 200 else ""))
        write_out("deepseek", "v19_effort_%s.json" % lvl, raw)
    report["v19"] = v19


# =========================================================================
# V-13b · 中断后到底扣不扣钱
#
# 用户反馈(2026-10-16):平台用量页**只能按模型/日期聚合**,查不到单笔请求,
# 所以"去后台核对"这条路走不通。改用程序化方案:
#   * OpenRouter:有 `GET /api/v1/generation?id=<gen_id>` 可查**单笔**的真实计费
#     → 这是精确答案(E1)。做法:同一 prompt 跑两次,一次跑完、一次读 5 行就断,
#       各自拿 gen_id 去查,直接对比 completion token 与 total_cost。
#   * DeepSeek:没有单笔查询端点,只有 `GET /user/balance`(账户余额)。
#     余额精度可能粗到看不出单笔差异 → 只做记录,结论标注为"不确定"。
#     但这个端点本身对产品有用(可做"余额/额度"展示)。
# =========================================================================
GEN_PROMPT = "用大约 400 字描写一座雨中的废弃车站,要有细节。"


def http_get(url, key, timeout=60):
    import urllib.request
    import urllib.error
    req = urllib.request.Request(url, method="GET")
    req.add_header("Authorization", "Bearer " + key)
    req.add_header("Accept", "application/json")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace"), time.time() - t0
    except Exception as e:
        body = ""
        try:
            body = e.read().decode("utf-8", "replace")
        except Exception:
            pass
        code = getattr(e, "code", -1)
        return code, body or ("EXCEPTION: %r" % (e,)), time.time() - t0


def _gen_id_from_lines(lines):
    for _, line in lines:
        if not line.startswith("data:"):
            continue
        payload = line[5:].strip()
        if not payload.startswith("{"):
            continue
        d = jget(payload)
        if d and d.get("id"):
            return d["id"]
    return None


def _query_generation(key, gen_id, tries=6, wait=4):
    """OpenRouter 的计费要几秒才落账,所以要轮询几次。"""
    url = "https://openrouter.ai/api/v1/generation?id=" + gen_id
    for i in range(tries):
        st, raw, _ = http_get(url, key)
        d = jget(raw)
        if st == 200 and d and (d.get("data") or {}).get("id"):
            return st, d["data"], raw
        time.sleep(wait)
    return st, None, raw


def probe_v13b_openrouter(key, report):
    cfg = PROVIDERS["openrouter"]
    say("\n[V-13b] OpenRouter · 中断后到底扣不扣钱(用 /api/v1/generation 精确查单笔)")

    out = {}
    for tag, abort in (("full", None), ("aborted", 5)):
        sse = post_sse(cfg, key,
                       body_of(cfg, GEN_PROMPT, stream=True, reasoning="off"),
                       abort_after_lines=abort)
        write_out("openrouter", "v13b_%s_raw.txt" % tag,
                  "\n".join("[%6dms] %s" % (ms, l) for ms, l in sse["lines"]))
        gid = _gen_id_from_lines(sse["lines"])
        # 流里自带的 usage(中断的那次拿不到,这正是问题所在)
        u = extract_usage("\n".join(l for _, l in sse["lines"])) or {}
        rec = {
            "gen_id": gid,
            "data_lines": sse["data_line_count"],
            "aborted": sse["aborted"],
            "total_ms": sse["total_ms"],
            "usage_in_stream": u or None,
        }
        say("     %-7s → data 行=%-3s · gen_id=%s · 流内 usage=%s"
            % (tag, sse["data_line_count"], gid, "有" if u else "无(中断就拿不到)"))

        if gid:
            say("              查询计费(要等几秒落账)…")
            st, data, raw = _query_generation(key, gid)
            write_out("openrouter", "v13b_%s_generation.json" % tag, raw)
            if data:
                rec["billed"] = {
                    "provider": data.get("provider_name"),
                    "tokens_prompt": data.get("tokens_prompt"),
                    "tokens_completion": data.get("tokens_completion"),
                    "native_tokens_prompt": data.get("native_tokens_prompt"),
                    "native_tokens_completion": data.get("native_tokens_completion"),
                    "total_cost": data.get("total_cost"),
                    "finish_reason": data.get("finish_reason"),
                    "native_finish_reason": data.get("native_finish_reason"),
                    "cancelled": data.get("cancelled"),
                    "streamed": data.get("streamed"),
                    "generation_time_ms": data.get("generation_time"),
                    "latency_ms": data.get("latency"),
                }
                say("              → completion token=%s · cost=$%s · cancelled=%s"
                    % (data.get("native_tokens_completion"),
                       data.get("total_cost"), data.get("cancelled")))
            else:
                rec["billed"] = {"error": "查询失败 HTTP %s" % st}
                say("              → 查询失败 HTTP %s" % st)
        out[tag] = rec

    # 直接给结论
    f = (out.get("full") or {}).get("billed") or {}
    a = (out.get("aborted") or {}).get("billed") or {}
    try:
        fc = float(f.get("native_tokens_completion") or 0)
        ac = float(a.get("native_tokens_completion") or 0)
        if fc > 0:
            ratio = ac / fc
            out["verdict_ratio"] = round(ratio, 3)
            out["verdict"] = ("中断**省钱**(只按已生成部分计费)" if ratio < 0.5
                              else "🔴 中断**不省钱**(按完整回复计费)" if ratio > 0.85
                              else "介于两者之间,需加大样本")
            say("     ⇒ 中断/完整 的 completion token 比 = %.2f → %s" % (ratio, out["verdict"]))
    except Exception:
        pass
    report["v13b_or"] = out


def probe_ds_balance(key, report):
    """V-20(新):DeepSeek 的余额端点 —— 顺便验证能否做"余额展示"功能。"""
    say("\n[V-20] DeepSeek · /user/balance 端点(余额展示功能的可行性)")
    st, raw, el = http_get("https://api.deepseek.com/user/balance", key)
    write_out("deepseek", "v20_balance.json", raw)
    d = jget(raw) or {}
    infos = d.get("balance_infos") or []
    report["v20_ds_balance"] = {
        "status": st, "elapsed_s": round(el, 2),
        "is_available": d.get("is_available"),
        "balance_infos": infos,
        "raw_preview": redact(raw)[:300],
    }
    say("     HTTP %s · is_available=%s · %s"
        % (st, d.get("is_available"),
           ", ".join("%s %s" % (i.get("currency"), i.get("total_balance")) for i in infos) or "—"))

    # 顺手测 DS 的中断是否扣钱:余额前后对比(精度可能不够,只做记录)
    if st == 200 and infos:
        def bal():
            _, r, _ = http_get("https://api.deepseek.com/user/balance", key)
            dd = jget(r) or {}
            bi = (dd.get("balance_infos") or [{}])[0]
            try:
                return float(bi.get("total_balance"))
            except Exception:
                return None

        cfg = PROVIDERS["deepseek"]
        say("     附:用余额差值估算 DS 的中断计费(精度可能不足,仅记录)")
        b0 = bal()
        sse = post_sse(cfg, key, body_of(cfg, GEN_PROMPT, stream=True, reasoning="off"),
                       abort_after_lines=5)
        time.sleep(6)
        b1 = bal()
        sse2 = post_sse(cfg, key, body_of(cfg, GEN_PROMPT, stream=True, reasoning="off"))
        u2 = extract_usage("\n".join(l for _, l in sse2["lines"])) or {}
        time.sleep(6)
        b2 = bal()
        report["v13b_ds"] = {
            "balance_before": b0,
            "balance_after_aborted": b1,
            "balance_after_full": b2,
            "delta_aborted": (None if (b0 is None or b1 is None) else round(b0 - b1, 6)),
            "delta_full": (None if (b1 is None or b2 is None) else round(b1 - b2, 6)),
            "aborted_data_lines": sse["data_line_count"],
            "full_usage": u2 or None,
            "note": "余额精度通常到分,单笔成本远小于一分 → 差值很可能都是 0,属于预期内的「测不出」",
        }
        say("        余额 %s → %s(中断后)→ %s(完整后);Δ中断=%s,Δ完整=%s"
            % (b0, b1, b2, report["v13b_ds"]["delta_aborted"], report["v13b_ds"]["delta_full"]))


# =========================================================================
# 报告
# =========================================================================
def render_report(report):
    L = []
    A = L.append
    A("# Mirage · M0 协议探针 #2 报告")
    A("")
    A("| 项 | 值 |")
    A("|---|---|")
    A("| 生成时间 | %s |" % report["ts"])
    A("| 本轮目标 | 解除 `V-12` 的前置阻塞(`V-17`),并补齐 DeepSeek 思考行为 |")
    A("| 脱敏 | ✅ 所有产出物已过 `redact()` |")
    A("")
    A("> 这份报告与 `out2/` 下的原始文件可以直接发给我。")
    A("")
    if report.get("step_errors"):
        A("## ⚠️ 本次有探测项异常(其余结果仍有效)")
        A("")
        A("| 项 | 异常 |")
        A("|---|---|")
        for e in report["step_errors"]:
            A("| `%s` | `%s` |" % (e.get("step"), e.get("error")))
        A("")

    # V-17
    A("## 🔴 V-17 · provider 锁定是否生效(最关键)")
    A("")
    if "v17" in report:
        A("锁定目标:`%s`(取自 A 组实际命中过的上游)" % report.get("v17_target"))
        A("")
        A("| 组 | # | HTTP | 实际 provider | 命中目标? | cost | 耗时 |")
        A("|---|---|---|---|---|---|---|")
        for r in report["v17"]:
            hit = "—" if r["group"] == "unlocked" else ("✅" if r.get("hit_target") else "❌")
            A("| %s | %d | %s | `%s` | %s | %s | %.1fs |" % (
                "不锁" if r["group"] == "unlocked" else "锁定",
                r["i"], r["status"], r.get("provider"), hit, r.get("cost"), r.get("elapsed_s") or 0))
        unlocked = [r.get("provider") for r in report["v17"] if r["group"] == "unlocked"]
        locked = [r for r in report["v17"] if r["group"] == "locked"]
        A("")
        A("**怎么看:**")
        A("")
        A("- A 组(不锁)命中的上游:%s → %s" % (
            unlocked,
            "**确认漂移**" if len(set(filter(None, unlocked))) > 1 else "本次恰好没漂(不代表不会漂,`docs/14 §10.4` 已有 4 次漂移记录)"))
        if locked:
            ok = sum(1 for r in locked if r.get("hit_target"))
            A("- B 组(锁定)命中目标 **%d/%d** → %s" % (
                ok, len(locked),
                "✅ **锁定生效,V-12 可以开跑**" if ok == len(locked) else "🔴 **锁定不可靠,V-12 的测法还要再改**"))
    else:
        A("_未执行(缺 OpenRouter Key 或 --no-net)_")
    A("")

    # V-12b
    A("## V-12b · 支持 tools 的模型有多少(零成本预筛)")
    A("")
    v = report.get("v12b") or {}
    if "error" in v:
        A("_%s_" % v["error"])
    elif v:
        A("| 项 | 值 |")
        A("|---|---|")
        A("| 模型总数 | %s |" % v["total"])
        A("| **声明支持 `tools`** | **%s(%.0f%%)** |" % (v["with_tools"], 100.0 * v["with_tools"] / max(1, v["total"])))
        A("| 不支持 | %s |" % v["without_tools"])
        A("| 免费且支持 tools | %s |" % len(v.get("free_with_tools") or []))
        A("| 我们配置的 OR 模型 `%s` 支持 tools? | %s |" % (
            v["configured_or_model"], "✅ 是" if v["configured_or_model_supports_tools"] else "❌ **否**"))
        A("")
        A("**最便宜的 15 个支持 tools 的模型**(我们的用户最可能用这一档,Step 4 应该拿它们测):")
        A("")
        A("| 模型 | 上下文 | prompt 单价 | completion 单价 | 也支持 reasoning? |")
        A("|---|---|---|---|---|")
        for m in v.get("cheapest_15_with_tools") or []:
            A("| `%s` | %s | %s | %s | %s |" % (
                m["id"], m["ctx"], m["prompt_price"], m["completion_price"],
                "是" if m["has_reasoning"] else "否"))
        if v.get("free_with_tools"):
            A("")
            A("**免费且支持 tools**(适合做大批量回归):`%s`" % "`, `".join(v["free_with_tools"]))
    A("")

    # V-12a
    A("## 🔴 V-12a · stream + tools 冒烟(M0 核心闸门)")
    A("")
    for prov, res in (report.get("v12a") or {}).items():
        A("### %s" % prov)
        A("")
        ns = res["nonstream"]
        A("**非流式 + tools:**")
        A("")
        A("| 项 | 值 |")
        A("|---|---|")
        A("| HTTP | %s |" % ns["status"])
        A("| finish_reason | `%s`%s |" % (ns["finish_reason"],
          (" (native: `%s`)" % ns["native_finish_reason"]) if ns.get("native_finish_reason") else ""))
        A("| 实际 provider | %s |" % (ns.get("provider") or "—"))
        A("| tool_calls 条数 | %s |" % ns["tool_call_count"])
        A("| 工具名 | `%s` |" % ns["tool_name"])
        A("| **arguments 可解析成合法 JSON?** | %s |" % ("✅ 是" if ns["args_parse_ok"] else "🔴 **否**"))
        A("| 正文预览 | %s |" % (ns["content_preview"] or "_(空)_"))
        if ns.get("error"):
            A("| 错误 | `%s` |" % ns["error"])
        A("")
        A("`arguments` 原文:")
        A("")
        A("```json")
        A(str(ns["tool_args_raw"])[:600])
        A("```")
        A("")
        st = res["stream"]
        A("**流式 + tools(重点):**")
        A("")
        A("| 项 | 值 | 含义 |")
        A("|---|---|---|")
        A("| HTTP | %s | |" % st["status"])
        A("| 首 data 延迟 / 总耗时 | %s ms / %s ms | |" % (st["first_data_ms"], st["total_ms"]))
        A("| data 行 / 注释行 | %s / %s | |" % (st["data_lines"], st["comment_lines"]))
        A("| 收到 `[DONE]`? | %s | |" % ("✅" if st["saw_done"] else "❌"))
        A("| finish_reason 序列 | `%s` | 应为 `tool_calls`;若是 `stop` 说明模型没调工具 |" % st["finish_reasons"])
        A("| 正文 delta 片数 | %s | 叙事与工具能否同时出 |" % st["content_delta_chunks"])
        A("| **工具片段总数** | **%s** | 0 = 流式下压根没返回工具调用 🔴 |" % st["tool_call_fragments"])
        A("| tool_call index | %s | 多工具并发时要靠它归集 |" % st["tool_call_indices"])
        A("| 工具名出现次数 | %s | >1 说明名字也被分片了 |" % st["name_appeared_times"])
        A("| **arguments 分片数** | **%s** | >1 必须跨 chunk 拼接 |" % st["arg_fragment_count"])
        A("| **拼接后可解析?** | %s | **这是方案 A 的生死线** |" % ("✅ 是" if st["joined_args_parse_ok"] else "🔴 **否**"))
        if st.get("error"):
            A("| 错误 | `%s` |  |" % st["error"])
        A("")
        A("拼接后的 `arguments`:")
        A("")
        A("```json")
        A(st["joined_args"] or "(空)")
        A("```")
        A("")

    # V-16 / V-18 / V-19
    # V-13b
    A("## 🔴 V-13b · 中断后到底扣不扣钱(改用程序化方案)")
    A("")
    A("> 平台用量页只能按模型/日期聚合、查不到单笔,所以改用 OpenRouter 的")
    A("> `GET /api/v1/generation?id=<gen_id>` —— 它能查**单笔**的真实计费。")
    A("")
    v = report.get("v13b_or") or {}
    if v:
        A("### OpenRouter(精确,E1)")
        A("")
        A("| 项 | 跑完整 | **读 5 行就断** |")
        A("|---|---|---|")
        f, a = v.get("full") or {}, v.get("aborted") or {}
        fb, ab = f.get("billed") or {}, a.get("billed") or {}
        A("| SSE data 行数 | %s | **%s** |" % (f.get("data_lines"), a.get("data_lines")))
        A("| 流内自带 usage | %s | %s |" % (
            "有" if f.get("usage_in_stream") else "无",
            "**无**(中断就拿不到,这正是要查后台的原因)" if not a.get("usage_in_stream") else "有"))
        A("| 实际 provider | %s | %s |" % (fb.get("provider"), ab.get("provider")))
        A("| **计费 completion token** | **%s** | **%s** |" % (
            fb.get("native_tokens_completion"), ab.get("native_tokens_completion")))
        A("| 计费 prompt token | %s | %s |" % (
            fb.get("native_tokens_prompt"), ab.get("native_tokens_prompt")))
        A("| **total_cost** | **$%s** | **$%s** |" % (fb.get("total_cost"), ab.get("total_cost")))
        A("| finish_reason | `%s` | `%s` |" % (fb.get("finish_reason"), ab.get("finish_reason")))
        A("| `cancelled` 字段 | %s | %s |" % (fb.get("cancelled"), ab.get("cancelled")))
        A("| 生成耗时 / 首字延迟 | %s / %s ms | %s / %s ms |" % (
            fb.get("generation_time_ms"), fb.get("latency_ms"),
            ab.get("generation_time_ms"), ab.get("latency_ms")))
        A("")
        if v.get("verdict"):
            A("**⇒ 中断/完整 的 completion token 比 = %s → %s**" % (v.get("verdict_ratio"), v["verdict"]))
        else:
            A("_比值无法计算(某一笔的计费数据没查到),看上表原始值。_")
        A("")
        A("**产品含义:**")
        A("")
        A("- 若中断**省钱** → 「停止生成」是一个真能省钱的按钮,UI 可以明示,甚至可以做")
        A("  「这次停止为你省了约 $X」这种正反馈。")
        A("- 若中断**不省钱** → UI **绝不能**暗示停止=省钱,否则是误导;")
        A("  并且要反过来优化:宁可把 `max_tokens` 调低,也别让用户靠中断控成本。")
        A("- ⭐ 另外这个端点本身有产品价值:`cancelled` / `native_finish_reason` / `latency`")
        A("  都能喂给 `docs/13 §12` 的「生成详情」面板。")
    else:
        A("_未执行(缺 OpenRouter Key)_")
    A("")
    d = report.get("v13b_ds")
    if d:
        A("### DeepSeek(只能用余额差值估算,精度可能不足)")
        A("")
        A("| 项 | 值 |")
        A("|---|---|")
        A("| 余额(起始) | %s |" % d.get("balance_before"))
        A("| 余额(中断一次后) | %s → **Δ = %s** |" % (d.get("balance_after_aborted"), d.get("delta_aborted")))
        A("| 余额(完整一次后) | %s → **Δ = %s** |" % (d.get("balance_after_full"), d.get("delta_full")))
        A("| 完整那次的 usage | `%s` |" % json.dumps(d.get("full_usage"), ensure_ascii=False))
        A("")
        A("> ⚠️ %s" % d.get("note"))
        A("> 若两个 Δ 都是 0,**这是预期结果,不是脚本坏了** —— 说明 DeepSeek 侧")
        A("> 无法用余额反推单笔,只能借 OpenRouter 的结论做类推(降级为 E3),或者等以后")
        A("> 跑大批量测试时用「N 次中断 vs N 次完整」的总额差来测。")
        A("")
    A("## V-20 · DeepSeek 余额端点(顺带:能不能做余额展示)")
    A("")
    b = report.get("v20_ds_balance")
    if b:
        A("| 项 | 值 |")
        A("|---|---|")
        A("| `GET /user/balance` | HTTP %s · %.1fs |" % (b["status"], b["elapsed_s"]))
        A("| `is_available` | %s |" % b.get("is_available"))
        for i in b.get("balance_infos") or []:
            A("| 余额(%s) | total=%s / granted=%s / topped_up=%s |" % (
                i.get("currency"), i.get("total_balance"),
                i.get("granted_balance"), i.get("topped_up_balance")))
        A("")
        A("> **产品含义:** 若可用,`A-4` 附近可以加一个「剩余额度」展示 ——")
        A("> BYOK 用户最关心的就是「我还剩多少钱」。但注意这是 **DeepSeek 的非标端点**,")
        A("> OpenRouter 用 `GET /api/v1/credits`,其他服务商可能没有 →")
        A("> **必须做成「能查就查、查不到就不显示」的可选能力**,不能当作通用字段。")
    else:
        A("_未执行_")
    A("")
    A("## V-16 · DeepSeek 不传思考参数时,默认思不思考")
    A("")
    if report.get("v16"):
        A("| 场景 | HTTP | 有 reasoning_content? | reasoning_tokens | prompt | completion | 缓存命中 | 耗时 |")
        A("|---|---|---|---|---|---|---|---|")
        for r in report["v16"]:
            A("| %s | %s | %s | %s | %s | %s | %s | %.1fs |" % (
                r["case"], r["status"], "✅ 有" if r["has_reasoning_content"] else "❌ 无",
                r["reasoning_tokens"], r["prompt_tokens"], r["completion_tokens"],
                r["cache_hit"], r["elapsed_s"]))
        A("")
        A("> **怎么看:** 若三个场景都「无」,则 DeepSeek 默认**不思考**,"
          "`/models` 里的 `default_level: high` 只是 effort 的默认档位而非「默认开启」;"
          "若推理场景「有」而简单场景「无」,则它是**按需自动触发**,"
          "那么 token 预算必须按「随时可能多花几十到几百 token」来留余量。")
    else:
        A("_未执行_")
    A("")

    A("## V-18 · DeepSeek 流式下的思考字段名")
    A("")
    if report.get("v18"):
        d = report["v18"]
        A("| 项 | 值 |")
        A("|---|---|")
        A("| HTTP | %s |" % d["status"])
        A("| **delta 出现过的字段** | `%s` |" % json.dumps(d["delta_keys"], ensure_ascii=False))
        A("| 首 data / 总耗时 | %s ms / %s ms |" % (d["first_data_ms"], d["total_ms"]))
        A("| 注释行 | %s |" % d["comment_lines"])
        A("| `[DONE]` | %s |" % ("✅" if d["saw_done"] else "❌"))
        A("")
        A("> **怎么看:** 字段里出现 `reasoning_content` → 与非流式同名,归一化层照 `docs/14 §10.7` 写即可;"
          "出现 `reasoning` → **DeepSeek 流式与非流式自己都不一致**,归一化层要多认一个名字。")
    else:
        A("_未执行_")
    A("")

    A("## V-19 · DeepSeek effort 三档")
    A("")
    if report.get("v19"):
        A("| effort | HTTP | reasoning_tokens | completion | prompt | 耗时 | 备注 |")
        A("|---|---|---|---|---|---|---|")
        for r in report["v19"]:
            A("| `%s` | %s | %s | %s | %s | %.1fs | %s |" % (
                r["effort"], r["status"], r["reasoning_tokens"], r["completion_tokens"],
                r["prompt_tokens"], r["elapsed_s"], r.get("error") or ""))
        A("")
        A("> **怎么看:** 若三档的 `reasoning_tokens` 明显递增,说明 `effort` 是**有效的成本旋钮**,"
          "可以做成 UI 上的「思考深度」三档(比布尔开关更有产品价值);"
          "若全部 HTTP 400,说明 `effort` 不是顶层参数,要换位置(嵌在 `thinking` 里)重试。")
    else:
        A("_未执行_")
    A("")

    A("---")
    A("")
    A("## 原始文件索引")
    A("")
    A("| 文件 | 内容 |")
    A("|---|---|")
    A("| `openrouter/v17_unlocked_*.json` | 不锁 provider 的 3 次响应 |")
    A("| `openrouter/v17_locked_*.json` | 锁定 provider 的 3 次响应 |")
    A("| `openrouter/v12b_tools_capable_models.json` | 支持 tools 的模型预筛结果 |")
    A("| `*/v12a_nonstream_tools.json` | 非流式 + tools |")
    A("| `*/v12a_stream_tools_raw.txt` | ⭐ **流式 + tools 的完整原始流**(带毫秒戳) |")
    A("| `deepseek/v16_default_*.json` | 不传思考参数的 3 个场景 |")
    A("| `deepseek/v18_stream_thinking_raw.txt` | 流式 + 开思考的原始流 |")
    A("| `deepseek/v19_effort_*.json` | effort 三档 |")
    A("| `openrouter/v13b_*_raw.txt` | V-13b 的两次原始流(完整 / 中断) |")
    A("| `openrouter/v13b_*_generation.json` | ⭐ **单笔真实计费查询结果** |")
    A("| `deepseek/v20_balance.json` | DeepSeek 余额端点响应 |")
    return "\n".join(L)


def resolve_keys():
    """读取并校验环境变量里的 Key。

    ⚠️ 2026-10-16 修复的 bug:`validate_key()` **内部已经调用了 `sanitize_key()`**,
    并返回 `(clean_key_or_None, messages)`。
    我原先在外面又调了一次 `sanitize_key()`(它返回元组 `(key, notes)`),
    把元组传了进去 → `AttributeError: 'tuple' object has no attribute 'strip'`。
    → 教训:复用别处的函数前先确认它的返回签名。此处只调 `validate_key` 一次。
    """
    keys = {}
    for name, cfg in PROVIDERS.items():
        raw = os.environ.get(cfg["env"])
        if not raw:
            say("\n⏭  跳过 %s:环境变量 %s 未设置" % (name, cfg["env"]))
            continue
        key, msgs = validate_key(name, cfg["env"], raw)
        for m in msgs:
            say("   [%s] %s" % (name, m))
        if not key:
            say("🔴 %s 的 Key 校验未通过,跳过该服务商(不发任何请求)" % name)
            continue
        keys[name] = key
    return keys


def run_step(name, fn, *args, **kwargs):
    """单项探测崩了不要连带丢掉整份报告 —— 第一轮已经吃过一次亏。"""
    try:
        fn(*args, **kwargs)
    except Exception as e:
        say("   🔴 [%s] 本项异常,已跳过:%r" % (name, e))
        import traceback
        traceback.print_exc()
        return {"step": name, "error": repr(e)}
    return None


def main():
    no_net = "--no-net" in sys.argv
    report = {"ts": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
              "step_errors": []}

    say("Mirage · M0 协议探针 #2")
    say("=" * 60)

    try:
        run_step("V-12b", probe_v12b, report)  # 零成本,总是跑

        if not no_net:
            keys = resolve_keys()
            if not keys:
                say("\n⚠️ 没有任何可用的 Key,只输出 V-12b 的离线结果。")

            if "openrouter" in keys:
                e = run_step("V-17", probe_v17, keys["openrouter"], report)
                if e:
                    report["step_errors"].append(e)
                target = report.get("v17_target")
                lock = {"provider": {"order": [target], "allow_fallbacks": False}} if target else None
                # 用锁定的 provider 测 tools —— 否则结论会被 provider 漂移污染
                e = run_step("V-12a/openrouter", probe_v12a,
                             "openrouter", keys["openrouter"], report, extra=lock)
                if e:
                    report["step_errors"].append(e)
                e = run_step("V-13b/openrouter", probe_v13b_openrouter,
                             keys["openrouter"], report)
                if e:
                    report["step_errors"].append(e)
            if "deepseek" in keys:
                e = run_step("V-12a/deepseek", probe_v12a, "deepseek", keys["deepseek"], report)
                if e:
                    report["step_errors"].append(e)
                e = run_step("V-20/deepseek-balance", probe_ds_balance, keys["deepseek"], report)
                if e:
                    report["step_errors"].append(e)
                e = run_step("V-16/18/19", probe_ds_reasoning, keys["deepseek"], report)
                if e:
                    report["step_errors"].append(e)
    finally:
        # 🔴 无论中间崩成什么样,报告都要落盘 —— 已经收集到的数据不能丢
        os.makedirs(OUT_DIR, exist_ok=True)
        path = os.path.join(OUT_DIR, "REPORT2.md")
        try:
            content = render_report(report)
        except Exception as e:
            content = "# 报告渲染失败\n\n`%r`\n\n原始数据见 `raw_report.json`。" % (e,)
        with open(path, "w", encoding="utf-8") as f:
            f.write(redact(content))
        with open(os.path.join(OUT_DIR, "raw_report.json"), "w", encoding="utf-8") as f:
            f.write(redact(json.dumps(report, ensure_ascii=False, indent=1, default=str)))

        say("")
        say("=" * 60)
        if report.get("step_errors"):
            say("⚠️ 有 %d 项探测异常(详情见报告顶部),其余结果仍然有效。" % len(report["step_errors"]))
        say("✅ 报告已写出:%s" % os.path.normpath(path))
        say("   把这份 REPORT2.md 发我即可。")


if __name__ == "__main__":
    main()
