#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Mirage · M0 协议探针 #3 (m0_probe3.py) —— 小而专,只干三件事

起因:第二支探针的 V-13b 挂了。`GET /api/v1/generation?id=<gen_id>` 两次都返回
      HTTP 404「Generation gen-xxx not found」,所以报告里计费字段全是 None。
      gen_id 提取正确、Authorization 头也正确 → 不是拼 URL 或鉴权的问题,
      最可能是「落账延迟 > 我的轮询窗口(6 次 x 4s = 20s)」。

本支探针的设计思路是**先诊断、再测量**:

  S1  零成本诊断:拿 out2 里那两个**几小时前**的 gen_id 重查一次。
        · 现在能查到 → 根因确认为落账延迟,修法就是加长轮询(并记录落账耗时)
        · 现在还 404 → 根因是端点/权限,generation 方案作废,直接走 S2 的 credits 差值
      这一步不发任何生成请求,不花钱,但能二选一地定死根因。

  S2  零成本:验证 OpenRouter 的额度端点 `GET /api/v1/credits` 与 `GET /api/v1/key`。
        · 关闭 V-20 的 OpenRouter 另一半(DeepSeek 侧已在第二轮关闭)
        · 更重要:`total_usage` 是浮点、精度远高于 DeepSeek 的「分」
          → 它可以当作 V-13 的**独立测量工具**,不依赖 generation 端点

  S3  低成本测量:用 credits 差值测「中断 vs 完整」到底扣多少钱。
        流程:读 credits → 跑完整 → 轮询 credits 看落账曲线 → 跑中断(读 5 行即断)
              → 再轮询 credits → 同时对两个新 gen_id 做长轮询(最多 90s)
        这样一次跑完同时拿到两套证据(credits 差值 + generation 单笔),互相校验。

成本:约 2 次 400 字生成 + 1 次被中断的生成 ≈ $0.00006,可忽略。
耗时:约 3~5 分钟(大部分时间在等落账,属于故意等待)。

用法:
    export MIRAGE_OR_KEY="sk-or-v1-..."
    python3 tools/m0_probe3.py                # 全跑
    python3 tools/m0_probe3.py --only s1      # 只跑零成本诊断(S1)
    python3 tools/m0_probe3.py --only s1,s2   # 只跑两个零成本项,完全不花钱
    python3 tools/m0_probe3.py --settle 120   # 落账轮询窗口改成 120 秒

产出:
    out3/openrouter/*.json|txt
    out3/REPORT3.md           <- 这份直接发我
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
    post_sse,
    extract_usage,
)
from m0_probe2 import (  # noqa: E402
    say,
    jget,
    body_of,
    http_get,
    _gen_id_from_lines,
    run_step,
    GEN_PROMPT,
)

OUT_DIR = os.path.join(HERE, "..", "out3")
OLD_OUT = os.path.join(HERE, "..", "out2", "openrouter")

CREDITS_URL = "https://openrouter.ai/api/v1/credits"
KEY_URL = "https://openrouter.ai/api/v1/key"
GEN_URL = "https://openrouter.ai/api/v1/generation?id="

# S3 的落账轮询窗口(秒),可用 --settle 覆盖
SETTLE_SECONDS = 90
SETTLE_STEP = 10


def write_out(name, content):
    d = os.path.join(OUT_DIR, "openrouter")
    os.makedirs(d, exist_ok=True)
    path = os.path.join(d, name)
    with open(path, "w", encoding="utf-8") as f:
        f.write(redact(content if isinstance(content, str) else str(content)))
    return path


# =========================================================================
# 小工具
# =========================================================================
def read_credits(key):
    """返回 (status, total_credits, total_usage, raw)。失败时数值为 None。"""
    st, raw, el = http_get(CREDITS_URL, key)
    d = jget(raw) or {}
    data = d.get("data") or d
    tc = data.get("total_credits")
    tu = data.get("total_usage")

    def f(x):
        try:
            return float(x)
        except Exception:
            return None

    return st, f(tc), f(tu), raw, round(el, 2)


def query_generation(key, gen_id):
    """单次查询,返回 (status, data_or_None, raw)。"""
    st, raw, _ = http_get(GEN_URL + gen_id, key)
    d = jget(raw)
    if st == 200 and d:
        data = d.get("data") or d
        if isinstance(data, dict) and data.get("id"):
            return st, data, raw
    return st, None, raw


def poll_generation(key, gen_id, window=None, step=5):
    """长轮询,记录每次尝试的 HTTP 码和第一次成功的耗时。"""
    window = window or SETTLE_SECONDS
    attempts = []
    t0 = time.time()
    last_raw = ""
    while True:
        el = time.time() - t0
        st, data, raw = query_generation(key, gen_id)
        last_raw = raw
        attempts.append({"at_s": round(el, 1), "status": st, "hit": bool(data)})
        if data:
            return {"found": True, "settled_after_s": round(el, 1),
                    "attempts": attempts, "data": data, "raw": raw}
        if el >= window:
            return {"found": False, "settled_after_s": None,
                    "attempts": attempts, "data": None, "raw": last_raw}
        time.sleep(step)


def billed_of(data):
    """把 generation 响应里我们关心的字段挑出来。"""
    if not data:
        return None
    return {
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
        "all_keys": sorted(data.keys()),
    }


def provider_from_lines(lines):
    """从流内取实际上游 —— 不要再依赖 generation 查询(那是第二轮的 bug 之一)。"""
    for _, line in lines:
        if not line.startswith("data:"):
            continue
        payload = line[5:].strip()
        if not payload.startswith("{"):
            continue
        d = jget(payload) or {}
        if d.get("provider"):
            return d["provider"]
    return None


def old_gen_ids():
    """从 out2 的原始流里捞出上一轮那两个 gen_id(几小时前的)。"""
    found = {}
    for tag in ("full", "aborted"):
        p = os.path.join(OLD_OUT, "v13b_%s_raw.txt" % tag)
        if not os.path.exists(p):
            continue
        try:
            with open(p, encoding="utf-8") as f:
                txt = f.read()
        except Exception:
            continue
        m = re.search(r'"id"\s*:\s*"(gen-[A-Za-z0-9\-]+)"', txt)
        if m:
            found[tag] = m.group(1)
    return found


# =========================================================================
# S1 · 零成本诊断:老 gen_id 现在还查得到吗
# =========================================================================
def probe_s1(key, report):
    say("\n[S1] 零成本诊断:重查上一轮那两个 gen_id(已过几小时)")
    ids = old_gen_ids()
    if not ids:
        say("     ⚠️ 没在 out2/openrouter/v13b_*_raw.txt 里找到 gen_id,跳过")
        report["s1"] = {"error": "未找到历史 gen_id"}
        return

    res = {}
    for tag, gid in ids.items():
        st, data, raw = query_generation(key, gid)
        write_out("s1_requery_%s.json" % tag, raw)
        res[tag] = {"gen_id": gid, "status": st, "found": bool(data),
                    "billed": billed_of(data),
                    "raw_preview": redact(raw)[:400]}
        say("     %-7s %s → HTTP %s · %s" % (tag, gid, st, "✅ 查到了" if data else "❌ 还是查不到"))
    report["s1"] = res

    any_found = any(v.get("found") for v in res.values())
    report["s1_verdict"] = ("落账延迟" if any_found else "端点或权限问题")
    say("     ⇒ 根因判定:%s" % report["s1_verdict"])


# =========================================================================
# S2 · 零成本:OpenRouter 额度端点
# =========================================================================
def probe_s2(key, report):
    say("\n[S2] 零成本:OpenRouter 额度端点(关闭 V-20 的 OR 这一半)")
    out = {}

    st, tc, tu, raw, el = read_credits(key)
    write_out("s2_credits.json", raw)
    out["credits"] = {"status": st, "elapsed_s": el,
                      "total_credits": tc, "total_usage": tu,
                      "remaining": (None if (tc is None or tu is None) else round(tc - tu, 8)),
                      "raw_preview": redact(raw)[:400]}
    say("     GET /credits → HTTP %s · %.2fs · total_credits=%s · total_usage=%s · 余额=%s"
        % (st, el, tc, tu, out["credits"]["remaining"]))

    st2, raw2, el2 = http_get(KEY_URL, key)
    write_out("s2_key.json", raw2)
    d2 = (jget(raw2) or {})
    data2 = d2.get("data") or d2
    out["key_info"] = {"status": st2, "elapsed_s": round(el2, 2),
                       "label": data2.get("label"),
                       "limit": data2.get("limit"),
                       "limit_remaining": data2.get("limit_remaining"),
                       "usage": data2.get("usage"),
                       "is_free_tier": data2.get("is_free_tier"),
                       "rate_limit": data2.get("rate_limit"),
                       "all_keys": sorted(data2.keys()) if isinstance(data2, dict) else None,
                       "raw_preview": redact(raw2)[:400]}
    say("     GET /key     → HTTP %s · limit=%s · limit_remaining=%s · usage=%s · free_tier=%s"
        % (st2, data2.get("limit"), data2.get("limit_remaining"),
           data2.get("usage"), data2.get("is_free_tier")))

    # 精度:credits 的 total_usage 小数位有多少,决定它能不能测单笔
    if tu is not None:
        s = repr(tu)
        dec = len(s.split(".")[1]) if "." in s else 0
        out["usage_decimals"] = dec
        say("     total_usage 小数位 = %d → %s"
            % (dec, "✅ 足够测单笔(单笔约 2e-05)" if dec >= 6 else "⚠️ 精度可能不够测单笔"))
    report["s2"] = out


# =========================================================================
# S3 · 低成本:中断 vs 完整,两套证据
# =========================================================================
def probe_s3(key, report, settle):
    cfg = PROVIDERS["openrouter"]
    say("\n[S3] 测量:中断 vs 完整(credits 差值 + generation 单笔,互相校验)")
    say("     注意:锁定 provider=Phala 以消除漂移干扰;若该上游繁忙,单次可能慢到 60s(已知代价)")

    lock = {"provider": {"order": ["Phala"], "allow_fallbacks": False}}
    out = {"settle_window_s": settle, "runs": [], "credits_curve": []}

    def snap(tag):
        st, tc, tu, raw, el = read_credits(key)
        rec = {"tag": tag, "status": st, "total_usage": tu, "total_credits": tc,
               "t": round(time.time(), 3)}
        out["credits_curve"].append(rec)
        say("       credits[%s] total_usage=%s" % (tag, tu))
        return tu

    u0 = snap("起始")

    for tag, abort in (("full", None), ("aborted", 5)):
        say("     --- %s ---" % ("跑完整" if abort is None else "读 5 行就断"))
        sse = post_sse(cfg, key, body_of(cfg, GEN_PROMPT, stream=True,
                                         reasoning="off", extra=lock),
                       abort_after_lines=abort)
        write_out("s3_%s_raw.txt" % tag,
                  "\n".join("[%6dms] %s" % (ms, l) for ms, l in sse["lines"]))
        gid = _gen_id_from_lines(sse["lines"])
        prov = provider_from_lines(sse["lines"])
        usage_in_stream = extract_usage("\n".join(l for _, l in sse["lines"])) or None
        say("       HTTP %s · data 行=%s · provider=%s · gen_id=%s · 流内 usage=%s"
            % (sse["status"], sse["data_line_count"], prov, gid,
               "有" if usage_in_stream else "无"))

        # 落账曲线:每 SETTLE_STEP 秒读一次 credits,直到 total_usage 变了或超时
        say("       等落账(最多 %ds)…" % settle)
        t0 = time.time()
        u_before = out["credits_curve"][-1]["total_usage"]
        u = u_before          # 防 NameError:若 settle 小于一个步长,下面的 while 可能一次都不进
        changed_at = None
        while time.time() - t0 < settle:
            time.sleep(SETTLE_STEP)
            u = snap("%s+%ds" % (tag, int(time.time() - t0)))
            if u is not None and u_before is not None and abs(u - u_before) > 1e-12:
                changed_at = round(time.time() - t0, 1)
                say("       → credits 在 %.1fs 后变化:%s → %s(Δ=%.8f)"
                    % (changed_at, u_before, u, u - u_before))
                break

        gen = None
        if gid:
            say("       长轮询 generation(最多 %ds)…" % settle)
            gen = poll_generation(key, gid, window=settle, step=5)
            write_out("s3_%s_generation.json" % tag, gen["raw"])
            if gen["found"]:
                say("       → generation 在 %.1fs 后可查:completion=%s · cost=$%s · cancelled=%s"
                    % (gen["settled_after_s"],
                       (gen["data"] or {}).get("native_tokens_completion"),
                       (gen["data"] or {}).get("total_cost"),
                       (gen["data"] or {}).get("cancelled")))
            else:
                say("       → generation %ds 内仍查不到(末次 HTTP %s)"
                    % (settle, gen["attempts"][-1]["status"] if gen["attempts"] else "?"))

        out["runs"].append({
            "tag": tag,
            "status": sse["status"],
            "data_lines": sse["data_line_count"],
            "aborted": sse["aborted"],
            "first_data_ms": sse["first_data_ms"],
            "total_ms": sse["total_ms"],
            "provider_in_stream": prov,
            "gen_id": gid,
            "usage_in_stream": usage_in_stream,
            "credits_delta": (None if (u is None or u_before is None) else round(u - u_before, 10)),
            "credits_changed_after_s": changed_at,
            "generation": ({"found": gen["found"],
                            "settled_after_s": gen["settled_after_s"],
                            "attempts": gen["attempts"],
                            "billed": billed_of(gen["data"])} if gen else None),
        })

    # 结论
    try:
        f = next(r for r in out["runs"] if r["tag"] == "full")
        a = next(r for r in out["runs"] if r["tag"] == "aborted")
        verdicts = []

        fb = ((f.get("generation") or {}).get("billed") or {})
        ab = ((a.get("generation") or {}).get("billed") or {})
        fc, ac = fb.get("native_tokens_completion"), ab.get("native_tokens_completion")
        if fc and ac is not None:
            ratio = float(ac) / float(fc)
            out["verdict_generation"] = {
                "full_completion": fc, "aborted_completion": ac, "ratio": round(ratio, 3),
                "text": ("中断省钱(只按已生成部分计费)" if ratio < 0.5
                         else "🔴 中断不省钱(按完整回复计费)" if ratio > 0.85
                         else "介于两者之间,需加大样本"),
            }
            verdicts.append("generation: ratio=%.2f" % ratio)

        fd, ad = f.get("credits_delta"), a.get("credits_delta")
        if fd and ad is not None and fd > 0:
            ratio2 = ad / fd
            out["verdict_credits"] = {
                "full_delta": fd, "aborted_delta": ad, "ratio": round(ratio2, 3),
                "text": ("中断省钱" if ratio2 < 0.5
                         else "🔴 中断不省钱" if ratio2 > 0.85 else "介于两者之间"),
            }
            verdicts.append("credits: ratio=%.2f" % ratio2)

        if verdicts:
            say("     ⇒ " + " / ".join(verdicts))
        if "verdict_generation" in out and "verdict_credits" in out:
            g = out["verdict_generation"]["ratio"]
            c = out["verdict_credits"]["ratio"]
            out["cross_check"] = ("✅ 两套证据一致" if abs(g - c) < 0.25
                                  else "⚠️ 两套证据不一致,以 generation 为准(它是单笔口径)")
            say("     ⇒ 交叉校验:%s" % out["cross_check"])
    except Exception as e:
        out["verdict_error"] = repr(e)

    report["s3"] = out


# =========================================================================
# 报告
# =========================================================================
def render_report(report):
    L = []
    A = L.append
    A("# Mirage · M0 协议探针 #3 报告(专修 V-13b)")
    A("")
    A("| 项 | 值 |")
    A("|---|---|")
    A("| 生成时间 | %s |" % report["ts"])
    A("| 本轮目标 | 查清 `generation` 端点 404 的根因,并用 credits 差值独立测「中断是否省钱」 |")
    A("| 跑了哪些步 | %s |" % ", ".join(report.get("ran") or []) or "—")
    A("| 脱敏 | ✅ 全部产出物已过 `redact()` |")
    A("")
    if report.get("step_errors"):
        A("## ⚠️ 本次有探测项异常(其余结果仍有效)")
        A("")
        A("| 项 | 异常 |")
        A("|---|---|")
        for e in report["step_errors"]:
            A("| `%s` | `%s` |" % (e.get("step"), e.get("error")))
        A("")

    # S1
    A("## 🔧 S1 · 根因诊断:上一轮那两个 gen_id 现在还查得到吗")
    A("")
    s1 = report.get("s1") or {}
    if "error" in s1:
        A("_%s_" % s1["error"])
    elif s1:
        A("| 上一轮的那次 | gen_id | HTTP | 现在查到了? | 计费 completion | total_cost | cancelled |")
        A("|---|---|---|---|---|---|---|")
        for tag, v in s1.items():
            b = v.get("billed") or {}
            A("| %s | `%s` | %s | %s | %s | %s | %s |" % (
                tag, v["gen_id"], v["status"], "✅" if v["found"] else "❌",
                b.get("native_tokens_completion"), b.get("total_cost"), b.get("cancelled")))
        A("")
        A("**根因判定:`%s`**" % report.get("s1_verdict"))
        A("")
        A("**怎么看:**")
        A("")
        A("- 若现在 ✅ 能查到 → 上一轮的 404 是**落账延迟**,我的轮询窗口(20s)太短。")
        A("  修法:轮询窗口拉到 90s,并把「落账耗时」本身记成一条产品约束")
        A("  (App 里若要显示单笔成本,必须接受**最多一两分钟的延迟**,不能指望请求一结束就有)。")
        A("- 若现在 ❌ 还查不到 → 是端点/权限问题(比如这个端点对流式请求不落单、")
        A("  或需要特定账号权限)。那么 generation 方案作废,**以 S3 的 credits 差值为唯一口径**。")
    else:
        A("_未执行_")
    A("")

    # S2
    A("## S2 · OpenRouter 额度端点(V-20 的 OR 一半)")
    A("")
    s2 = report.get("s2") or {}
    if s2:
        c = s2.get("credits") or {}
        k = s2.get("key_info") or {}
        A("| 端点 | HTTP | 耗时 | 关键字段 |")
        A("|---|---|---|---|")
        A("| `GET /api/v1/credits` | %s | %ss | total_credits=%s · total_usage=%s · **余额=%s** |" % (
            c.get("status"), c.get("elapsed_s"), c.get("total_credits"),
            c.get("total_usage"), c.get("remaining")))
        A("| `GET /api/v1/key` | %s | %ss | limit=%s · limit_remaining=%s · usage=%s · free_tier=%s |" % (
            k.get("status"), k.get("elapsed_s"), k.get("limit"),
            k.get("limit_remaining"), k.get("usage"), k.get("is_free_tier")))
        A("")
        if k.get("rate_limit") is not None:
            A("`/key` 返回的 rate_limit:`%s`" % json.dumps(k.get("rate_limit"), ensure_ascii=False))
            A("")
        if k.get("all_keys"):
            A("`/key` 返回的全部字段:`%s`" % "`, `".join(k["all_keys"]))
            A("")
        if s2.get("usage_decimals") is not None:
            A("`total_usage` 小数位 = **%d** → %s" % (
                s2["usage_decimals"],
                "✅ 精度足够测单笔(单笔约 2e-05 USD)" if s2["usage_decimals"] >= 6
                else "⚠️ 精度不足,单笔测不出"))
            A("")
        A("**产品含义:**")
        A("")
        A("- 两家的额度端点都能用 → 「剩余额度」可以做成真功能(`A-17`)。")
        A("  但字段完全不同:DeepSeek 是 `balance_infos[].total_balance`(CNY,精度到分),")
        A("  OpenRouter 是 `total_credits - total_usage`(USD,高精度),")
        A("  → 必须做成**每个 Provider 模板各自实现的可选能力**,查不到就不显示。")
        A("- `/key` 的 `limit` / `limit_remaining` 还能支撑一个更有用的功能:")
        A("  **用户设的消费上限,App 能读出来并展示进度条**。")
    else:
        A("_未执行_")
    A("")

    # S3
    A("## 🔴 S3 · 中断 vs 完整:到底扣不扣钱")
    A("")
    s3 = report.get("s3") or {}
    if s3:
        A("| 项 | 跑完整 | 读 5 行就断 |")
        A("|---|---|---|")
        runs = {r["tag"]: r for r in s3.get("runs") or []}
        f, a = runs.get("full") or {}, runs.get("aborted") or {}

        def row(label, kf, ka=None, fmt="%s"):
            ka = ka or kf
            A("| %s | %s | %s |" % (label, fmt % (f.get(kf),), fmt % (a.get(ka),)))

        row("SSE data 行数", "data_lines")
        row("实际 provider(取自流内)", "provider_in_stream")
        row("首 data 延迟 ms", "first_data_ms")
        row("总耗时 ms", "total_ms")
        A("| 流内自带 usage | %s | %s |" % (
            "有" if f.get("usage_in_stream") else "**无**",
            "有" if a.get("usage_in_stream") else "**无**(中断就拿不到,这正是要查后台的原因)"))
        fb = ((f.get("generation") or {}) or {}).get("billed") or {}
        ab = ((a.get("generation") or {}) or {}).get("billed") or {}
        A("| generation 查到了? | %s | %s |" % (
            "✅ %ss 后" % (f.get("generation") or {}).get("settled_after_s")
            if (f.get("generation") or {}).get("found") else "❌",
            "✅ %ss 后" % (a.get("generation") or {}).get("settled_after_s")
            if (a.get("generation") or {}).get("found") else "❌"))
        A("| **计费 completion token** | **%s** | **%s** |" % (
            fb.get("native_tokens_completion"), ab.get("native_tokens_completion")))
        A("| 计费 prompt token | %s | %s |" % (
            fb.get("native_tokens_prompt"), ab.get("native_tokens_prompt")))
        A("| **total_cost** | **$%s** | **$%s** |" % (fb.get("total_cost"), ab.get("total_cost")))
        A("| `cancelled` 字段 | %s | %s |" % (fb.get("cancelled"), ab.get("cancelled")))
        A("| finish_reason(后台口径) | `%s` | `%s` |" % (
            fb.get("finish_reason"), ab.get("finish_reason")))
        A("| 生成耗时 / 首字延迟(后台口径) | %s / %s ms | %s / %s ms |" % (
            fb.get("generation_time_ms"), fb.get("latency_ms"),
            ab.get("generation_time_ms"), ab.get("latency_ms")))
        A("| **credits 差值(USD)** | **%s** | **%s** |" % (
            f.get("credits_delta"), a.get("credits_delta")))
        A("| credits 多久后变化 | %ss | %ss |" % (
            f.get("credits_changed_after_s"), a.get("credits_changed_after_s")))
        A("")
        if s3.get("verdict_generation"):
            v = s3["verdict_generation"]
            A("**口径 1(generation 单笔,E1 最硬):** 中断/完整 completion token 比 = **%.2f** → %s"
              % (v["ratio"], v["text"]))
            A("")
        if s3.get("verdict_credits"):
            v = s3["verdict_credits"]
            A("**口径 2(credits 差值,独立证据):** 中断/完整 花费比 = **%.2f** → %s"
              % (v["ratio"], v["text"]))
            A("")
        if s3.get("cross_check"):
            A("**交叉校验:** %s" % s3["cross_check"])
            A("")
        if fb.get("all_keys"):
            A("`generation` 端点返回的全部字段(决定「生成详情」面板能展示什么):")
            A("")
            A("`%s`" % "`, `".join(fb["all_keys"]))
            A("")
        A("**产品含义:**")
        A("")
        A("- 中断**省钱** → 「停止生成」是真能省钱的按钮,UI 可以明示。")
        A("- 中断**不省钱** → UI **绝不能**暗示停止=省钱;要反过来靠调低 `max_tokens` 控成本。")
        A("- 不管哪种结论,**落账延迟**都是硬约束:成本数字不能做成「请求一结束就显示」。")
    else:
        A("_未执行_")
    A("")

    A("---")
    A("")
    A("## 原始文件索引")
    A("")
    A("| 文件 | 内容 |")
    A("|---|---|")
    A("| `openrouter/s1_requery_*.json` | 重查上一轮 gen_id 的响应(诊断用) |")
    A("| `openrouter/s2_credits.json` | `/api/v1/credits` 原始响应 |")
    A("| `openrouter/s2_key.json` | `/api/v1/key` 原始响应 |")
    A("| `openrouter/s3_*_raw.txt` | 完整 / 中断 两次的原始 SSE(带毫秒戳) |")
    A("| `openrouter/s3_*_generation.json` | ⭐ 单笔真实计费 |")
    A("| `raw_report3.json` | 本报告的全部原始数据 |")
    return "\n".join(L) + "\n"


def main():
    global SETTLE_SECONDS
    argv = sys.argv[1:]
    only = None
    settle = SETTLE_SECONDS
    for i, a in enumerate(argv):
        if a == "--only" and i + 1 < len(argv):
            only = {x.strip().lower() for x in argv[i + 1].split(",") if x.strip()}
        if a == "--settle" and i + 1 < len(argv):
            try:
                settle = int(argv[i + 1])
            except Exception:
                pass
    SETTLE_SECONDS = settle

    want = lambda s: (only is None) or (s in only)  # noqa: E731

    report = {"ts": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
              "step_errors": [], "ran": []}

    say("Mirage · M0 协议探针 #3(专修 V-13b)")
    say("=" * 60)
    if only:
        say("只跑:%s" % ", ".join(sorted(only)))

    try:
        raw = os.environ.get(PROVIDERS["openrouter"]["env"])
        if not raw:
            say("\n🔴 环境变量 %s 未设置 —— 本支探针全部步骤都需要 OpenRouter Key。"
                % PROVIDERS["openrouter"]["env"])
            return
        key, msgs = validate_key("openrouter", PROVIDERS["openrouter"]["env"], raw)
        for m in msgs:
            say("   [openrouter] %s" % m)
        if not key:
            say("🔴 Key 校验未通过,不发任何请求。")
            return

        if want("s1"):
            report["ran"].append("S1")
            e = run_step("S1", probe_s1, key, report)
            if e:
                report["step_errors"].append(e)
        if want("s2"):
            report["ran"].append("S2")
            e = run_step("S2", probe_s2, key, report)
            if e:
                report["step_errors"].append(e)
        if want("s3"):
            report["ran"].append("S3")
            e = run_step("S3", probe_s3, key, report, settle)
            if e:
                report["step_errors"].append(e)
    finally:
        os.makedirs(OUT_DIR, exist_ok=True)
        path = os.path.join(OUT_DIR, "REPORT3.md")
        try:
            content = render_report(report)
        except Exception as e:
            content = "# 报告渲染失败\n\n`%r`\n\n原始数据见 `raw_report3.json`。" % (e,)
        with open(path, "w", encoding="utf-8") as f:
            f.write(redact(content))
        with open(os.path.join(OUT_DIR, "raw_report3.json"), "w", encoding="utf-8") as f:
            f.write(redact(json.dumps(report, ensure_ascii=False, indent=1, default=str)))
        say("")
        say("=" * 60)
        if report.get("step_errors"):
            say("⚠️ 有 %d 项异常(详情见报告顶部),其余结果仍有效。" % len(report["step_errors"]))
        say("✅ 报告已写出:%s" % os.path.normpath(path))
        say("   把这份 REPORT3.md 发我即可。")


if __name__ == "__main__":
    main()
