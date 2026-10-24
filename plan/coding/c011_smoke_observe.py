#!/usr/bin/env python3
"""C011 冒烟观察脚本（只读）。清单见 plan/coding/C011-smoke-test.md。

两个子命令：

  watch   冒烟期间在后台跑：订阅 GET /api/events，按秒轮询 GET /api/browser 和每个 Job 的
          GET /api/jobs/<id>/application，只把结构字段（时间、事件名、工具名、状态、插话条数、连接代数）
          追加到 --out 指定的 jsonl。工具调用的开始 / 结束用本机收到事件的毫秒时间记。
  report  读 watch 的记录（可选）、应用日志（可选）、storage/runs/<job>.json 和
          storage/projects/<project>/jobs/<job>/run-log.jsonl，再查一次 HTTP 接口，打印 (a)–(e) 五张表。

不打印：填写的值、工具参数、工具结果正文、模型文字、页面标题、插话原文。只打印工具名、去掉查询参数的网址、
状态、计数、时间，以及插话里 SMOKE-xxx 这样的标记。只用 Python 标准库；不连 Chrome，不调 MCP。

用法：
  python3 plan/coding/c011_smoke_observe.py watch  --out REC.jsonl JOB1 JOB2 JOB3
  python3 plan/coding/c011_smoke_observe.py report --rec REC.jsonl [--app-log APP.log] [--since ISO] JOB1 JOB2 JOB3
"""

import argparse
import json
import re
import signal
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime
from itertools import combinations
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DEFAULT_STORAGE = REPO / "storage"
DEFAULT_BASE = "http://127.0.0.1:8080"

INSERTED = "用户插话："      # ApplicationRunner.INSERTED
REPLIED = "用户回复："        # ApplicationRunner.reply 的普通回复
MARKER = re.compile(r"SMOKE-[A-Z0-9]+")
URL_IN_TEXT = re.compile(r"https?://[^\s)）\"'<>，。]+")
APP_LOG_TOOL = re.compile(r"浏览器工具 (\S+)，(\d+) ms")
APP_LOG_TS = re.compile(r"^(\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?)")
SUBMITTED_PREP = {"user_submitting", "verification_pending"}
# BrowserToolPolicy.MULTI_PART_SUFFIXES
MULTI_PART = {"com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn", "com.hk", "com.tw", "com.sg", "com.au",
              "net.au", "org.au", "co.uk", "org.uk", "ac.uk", "co.jp", "ne.jp", "or.jp", "co.kr", "co.in", "co.nz",
              "com.br", "com.mx"}


# ---------- 小工具 ----------

def now_ms():
    return int(time.time() * 1000)


def iso_ms(value):
    """ISO 时间 → 毫秒；解析不了返回 None。"""
    if not value:
        return None
    v = value.strip().replace(" ", "T")
    if v.endswith("Z"):
        v = v[:-1] + "+00:00"
    m = re.match(r"(.*[+-]\d{2})(\d{2})$", v)
    if m and ":" not in v[-6:]:
        v = m.group(1) + ":" + m.group(2)
    try:
        dt = datetime.fromisoformat(v)
    except ValueError:
        return None
    if dt.tzinfo is None:
        dt = dt.astimezone()
    return int(dt.timestamp() * 1000)


def clock(ms):
    return datetime.fromtimestamp(ms / 1000).strftime("%H:%M:%S.%f")[:-3] if ms else "-"


def strip_url(url):
    """scheme://host[:port]/path（去掉用户名密码、查询参数、片段），同 RunLog.urlPath。"""
    if not url:
        return None
    cut = re.split(r"[?#]", url.strip(), maxsplit=1)[0]
    m = re.match(r"^([a-zA-Z][a-zA-Z0-9+.-]*)://(?:[^@/]*@)?([^/:]+)(:\d+)?(/.*)?$", cut)
    if not m:
        return cut
    return "%s://%s%s%s" % (m.group(1), m.group(2), m.group(3) or "", m.group(4) or "/")


def host_of(url):
    m = re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*://(?:[^@/]*@)?([^/:?#]+)", url or "")
    return m.group(1).lower().rstrip(".") if m else None


def site_of(url):
    """同 BrowserToolPolicy.siteOf：最后两段，公共后缀是两段时取三段。"""
    host = host_of(url)
    if not host:
        return None
    if host.startswith("[") or re.fullmatch(r"[0-9.]+", host):
        return host
    labels = host.split(".")
    if len(labels) <= 2:
        return host
    keep = 3 if ".".join(labels[-2:]) in MULTI_PART else 2
    return ".".join(labels[-keep:])


def short(text, width):
    text = text or "-"
    return text if len(text) <= width else text[:width - 1] + "…"


def table(headers, rows):
    rows = [[str(c) for c in r] for r in rows]
    widths = [len(h) for h in headers]
    for r in rows:
        for i, c in enumerate(r):
            widths[i] = max(widths[i], len(c))
    line = "  ".join(h.ljust(widths[i]) for i, h in enumerate(headers))
    print(line)
    print("  ".join("-" * w for w in widths))
    for r in rows:
        print("  ".join(c.ljust(widths[i]) for i, c in enumerate(r)))
    if not rows:
        print("（无）")


def get_json(base, path, timeout=5):
    """GET → (json, None) 或 (None, 错误说明)。错误说明只含状态码 / 异常类名。"""
    try:
        with urllib.request.urlopen(base + path, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8")), None
    except urllib.error.HTTPError as e:
        return None, "HTTP %d" % e.code
    except Exception as e:  # noqa: BLE001 —— 只报类名
        return None, type(e).__name__


# ---------- watch ----------

class Recorder:
    def __init__(self, out, jobs):
        self.out = open(out, "a", encoding="utf-8")
        self.jobs = set(jobs)
        self.lock = threading.Lock()
        self.stop = threading.Event()

    def write(self, record):
        record = dict(record, t=now_ms())
        with self.lock:
            self.out.write(json.dumps(record, ensure_ascii=False) + "\n")
            self.out.flush()
        return record

    def say(self, text):
        print("%s %s" % (clock(now_ms()), text), flush=True)


def sse_loop(rec, base):
    """订阅全局事件流，只留结构字段。断了 2 秒后重连（不带 Last-Event-ID：重连期间的事件会漏，记一条 gap）。"""
    while not rec.stop.is_set():
        try:
            req = urllib.request.Request(base + "/api/events", headers={"Accept": "text/event-stream"})
            with urllib.request.urlopen(req, timeout=60) as resp:
                rec.write({"src": "sse", "event": "_connected"})
                name, data = None, []
                for raw in resp:
                    if rec.stop.is_set():
                        return
                    line = raw.decode("utf-8", "replace").rstrip("\r\n")
                    if line.startswith("event:"):
                        name = line[6:].strip()
                    elif line.startswith("data:"):
                        data.append(line[5:].lstrip())
                    elif line == "":
                        if name and data:
                            handle_event(rec, name, "\n".join(data))
                        name, data = None, []
        except Exception as e:  # noqa: BLE001
            if rec.stop.is_set():
                return
            rec.write({"src": "sse", "event": "_gap", "error": type(e).__name__})
            time.sleep(2)


def handle_event(rec, name, payload):
    try:
        data = json.loads(payload)
    except ValueError:
        return
    job = data.get("job")
    if name == "browser":
        b = data.get("browser") or {}
        r = rec.write({"src": "sse", "event": "browser", "state": b.get("state"), "generation": b.get("generation")})
        rec.say("浏览器 %s 代数 %s" % (r["state"], r["generation"]))
        return
    if job not in rec.jobs:
        return
    if name == "activity":
        a = data.get("activity") or {}
        rec.write({"src": "sse", "event": "activity", "job": job, "kind": a.get("kind"), "tool": a.get("name")})
    elif name == "step":
        s = data.get("step") or {}
        if s.get("kind") == "tool":
            rec.write({"src": "sse", "event": "step", "job": job, "tool": s.get("name"), "status": s.get("status")})
    elif name == "status":
        r = rec.write({"src": "sse", "event": "status", "job": job, "status": data.get("status")})
        rec.say("%s 状态事件 %s" % (job, r["status"]))
    elif name == "changed":
        rec.write({"src": "sse", "event": "changed", "job": job})


def poll_loop(rec, base, interval):
    last = {}
    while not rec.stop.is_set():
        b, err = get_json(base, "/api/browser")
        key = ("browser", None if b is None else (b.get("state"), b.get("generation")), err)
        r = rec.write({"src": "poll", "what": "browser", "state": b and b.get("state"),
                       "generation": b and b.get("generation"), "error": err})
        if last.get("browser") != key:
            rec.say("轮询 浏览器 %s 代数 %s%s" % (r["state"], r["generation"], " " + err if err else ""))
            last["browser"] = key
        for job in sorted(rec.jobs):
            a, err = get_json(base, "/api/jobs/%s/application" % job)
            row = {"src": "poll", "what": "app", "job": job, "error": err,
                   "status": a and a.get("status"), "prepState": a and a.get("prepState"),
                   "inserted": len((a or {}).get("inserted") or [])}
            rec.write(row)
            k = (row["status"], row["prepState"], row["inserted"], err)
            if last.get(job) != k:
                rec.say("轮询 %s status=%s prep=%s 未取走插话=%d%s" % (job, row["status"], row["prepState"],
                                                               row["inserted"], " " + err if err else ""))
                last[job] = k
        rec.stop.wait(interval)


def cmd_watch(args):
    rec = Recorder(args.out, args.jobs)
    rec.write({"src": "watch", "event": "_start", "jobs": sorted(args.jobs)})
    rec.say("开始记录 → %s（Ctrl-C 结束）" % args.out)
    signal.signal(signal.SIGTERM, lambda *_: rec.stop.set())
    threads = [threading.Thread(target=sse_loop, args=(rec, args.base), daemon=True),
               threading.Thread(target=poll_loop, args=(rec, args.base, args.interval), daemon=True)]
    for t in threads:
        t.start()
    deadline = time.time() + args.duration if args.duration else None
    try:
        while not rec.stop.is_set() and (deadline is None or time.time() < deadline):
            time.sleep(0.5)
    except KeyboardInterrupt:
        pass
    rec.stop.set()
    rec.write({"src": "watch", "event": "_stop"})
    rec.say("结束记录")


# ---------- report：读数据 ----------

def load_rec(path):
    if not path:
        return []
    out = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                try:
                    out.append(json.loads(line))
                except ValueError:
                    pass
    return out


def load_run(storage, job):
    p = storage / "runs" / ("%s.json" % job)
    if not p.exists():
        return None
    return json.loads(p.read_text(encoding="utf-8"))


def load_runlog(storage, project, job):
    if not project:
        return []
    p = storage / "projects" / project / "jobs" / job / "run-log.jsonl"
    if not p.exists():
        return []
    out = []
    for line in p.read_text(encoding="utf-8").splitlines():
        if line.strip():
            try:
                e = json.loads(line)
            except ValueError:
                continue
            # 只留结构字段
            out.append({k: e.get(k) for k in ("ts", "kind", "tool", "url_path", "decision", "outcome", "step")})
    return out


def sse_intervals(rec, jobs):
    """activity(kind=tool) 到同一个 Job 的下一个 step(tool) 之间：本机收到事件的毫秒时间。"""
    out = {j: [] for j in jobs}
    open_ = {}
    for r in rec:
        if r.get("src") != "sse" or r.get("job") not in out:
            continue
        j = r["job"]
        if r.get("event") == "activity":
            if r.get("kind") == "tool":
                open_[j] = (r["t"], r.get("tool"))
            else:
                open_.pop(j, None)
        elif r.get("event") == "step" and j in open_:
            start, tool = open_.pop(j)
            if tool == r.get("tool"):
                out[j].append((start, r["t"], tool))
    return out


def file_intervals(run, since):
    """运行记录里的工具结果：startedAt（精确到秒）+ ms。返回 (秒起点, ms, 工具名)。"""
    out = []
    for turn in (run or {}).get("conversation") or []:
        for res in turn.get("results") or []:
            s = iso_ms(res.get("startedAt"))
            if s is None or res.get("ms") is None:
                continue
            if since and s < since:
                continue
            out.append((s, int(res["ms"]), res.get("name")))
    return out


def app_log_facts(path):
    """应用日志里和连接有关的几行的条数（不打印行内容）。"""
    facts = {"启动浏览器 MCP（服务进程）": 0, "使用者的浏览器会话断了": 0, "日志里含「浏览器…调用失败」的行": 0}
    if not path:
        return facts
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            if "启动浏览器 MCP" in line:
                facts["启动浏览器 MCP（服务进程）"] += 1
            elif "的浏览器会话断了" in line:
                facts["使用者的浏览器会话断了"] += 1
            elif "调用失败" in line and "浏览器" in line:
                facts["日志里含「浏览器…调用失败」的行"] += 1
    return facts


def app_log_intervals(path, since):
    """应用日志「浏览器工具 X，N ms」：行时间是调用结束（毫秒），开始 = 结束 - N。按线程分。"""
    out = {}
    if not path:
        return out
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = APP_LOG_TOOL.search(line)
            ts = APP_LOG_TS.match(line)
            if not m or not ts:
                continue
            end = iso_ms(ts.group(1))
            if end is None or (since and end < since):
                continue
            head = line.split(" : ", 1)[0]
            brackets = re.findall(r"\[([^\]]*)\]", head)
            thread = brackets[-1].strip() if brackets else "?"
            out.setdefault(thread, []).append((end - int(m.group(2)), end, m.group(1)))
    return out


def overlaps(a, b):
    """两组 (start, end, tool) 的重叠：[(重叠 ms, a 项, b 项)]。"""
    out = []
    for x in a:
        for y in b:
            ov = min(x[1], y[1]) - max(x[0], y[0])
            if ov > 0:
                out.append((ov, x, y))
    return out


def browser_only(items, tool_index):
    return [i for i in items if (i[tool_index] or "").startswith("browser_")]


# ---------- report：五张表 ----------

def report_overlap(jobs, rec, runs, app_log, since):
    print("\n## (a) 两个 Worker 的浏览器动作是否同时进行\n")
    sse = sse_intervals(rec, jobs)
    if rec:
        print("来源 1（强）：watch 记录的事件流，开始 = 收到 activity(tool)，结束 = 收到 step；本机毫秒时钟。")
        rows = []
        for a, b in combinations(jobs, 2):
            ovs = overlaps(browser_only(sse[a], 2), browser_only(sse[b], 2))
            top = max(ovs, key=lambda o: o[0]) if ovs else None
            rows.append([a, b, len(browser_only(sse[a], 2)), len(browser_only(sse[b], 2)), len(ovs),
                         top[0] if top else 0,
                         "%s %s–%s / %s %s–%s" % (top[1][2], clock(top[1][0]), clock(top[1][1]),
                                                  top[2][2], clock(top[2][0]), clock(top[2][1])) if top else "-"])
        table(["Job A", "Job B", "A 浏览器调用", "B 浏览器调用", "重叠次数", "最大重叠 ms", "最大那一对"], rows)
    else:
        print("来源 1（强）：没有 --rec，跳过。")

    if app_log is not None:
        print("\n来源 2（强，MCP 层）：应用日志「浏览器工具 X，N ms」，按线程；结束 = 行时间（毫秒），开始 = 结束 - N。"
              "\n线程名不带 Job；application-worker-* 之间的重叠就是两个申请同时在调 MCP（含每次动作前的标签页核对和快照）。")
        threads = sorted(t for t in app_log if "worker" in t)
        rows = []
        for a, b in combinations(threads, 2):
            ovs = overlaps(app_log[a], app_log[b])
            top = max(ovs, key=lambda o: o[0]) if ovs else None
            rows.append([a, b, len(app_log[a]), len(app_log[b]), len(ovs), top[0] if top else 0,
                         "%s / %s @%s" % (top[1][2], top[2][2], clock(max(top[1][0], top[2][0]))) if top else "-"])
        table(["线程 A", "线程 B", "A 调用", "B 调用", "重叠次数", "最大重叠 ms", "最大那一对"], rows)
        others = sorted(t for t in app_log if "worker" not in t)
        if others:
            print("其它线程的浏览器调用：" + ", ".join("%s×%d" % (t, len(app_log[t])) for t in others))
        slow = [(max(i[1] - i[0] for i in items), t) for t, items in app_log.items() if items]
        print("每个线程最长的一次调用：" + (", ".join("%s %d ms" % (t, ms) for ms, t in sorted(slow, reverse=True))
                                     or "-") + "（≥30000 ms 要查是不是已知问题 K1 的卡住）")

    print("\n来源 3（弱）：storage/runs/<job>.json 工具结果的 startedAt（只到秒）+ ms。"
          "\n真实开始在 [startedAt, startedAt+1s) 里；「一定重叠」= 不管落在这一秒哪里都重叠，「可能重叠」= 有可能。"
          "\n只含每个 Job 最近一次开始之后、已经存盘的轮次。")
    rows = []
    for a, b in combinations(jobs, 2):
        fa = browser_only(file_intervals(runs.get(a), since), 2)
        fb = browser_only(file_intervals(runs.get(b), since), 2)
        sure, maybe, best = 0, 0, 0
        for x in fa:
            for y in fb:
                corners = []
                for da in (0, 999):
                    for db in (0, 999):
                        sa, sb = x[0] + da, y[0] + db
                        corners.append(min(sa + x[1], sb + y[1]) - max(sa, sb))
                if min(corners) > 0:
                    sure += 1
                    best = max(best, min(corners))
                elif max(corners) > 0:
                    maybe += 1
        rows.append([a, b, len(fa), len(fb), sure, maybe, best])
    table(["Job A", "Job B", "A 浏览器调用", "B 浏览器调用", "一定重叠", "可能重叠", "一定重叠的最小保证 ms"], rows)


def report_tabs(jobs, runs, logs, since):
    print("\n## (b) 每个 Job 的动作落在哪些页面（自己的标签页）\n")
    print("来源：run-log.jsonl 里浏览器调用后的页面地址（动作之后在自己的会话里取的快照），"
          "\n以及模型调用 browser_tabs list 时返回列表里的网址（应该只有自己的页）。网址去掉了查询参数。")
    own = {}
    for j in jobs:
        url = (runs.get(j) or {}).get("url")
        own[j] = (site_of(url), strip_url(url))
    for j in jobs:
        site, posting = own[j]
        others = {k: own[k] for k in jobs if k != j}
        other_sites = {s for s, _ in others.values() if s and s != site}
        other_postings = {p for _, p in others.values() if p and p != posting}
        counts = {}
        for e in logs.get(j, []):
            if e.get("kind") != "tool_call" or not e.get("url_path"):
                continue
            t = iso_ms(e.get("ts"))
            if since and t is not None and t < since:
                continue
            counts[e["url_path"]] = counts.get(e["url_path"], 0) + 1
        print("\n%s  自己的网站=%s  岗位地址=%s" % (j, site or "?", short(posting, 90)))
        rows = []
        for u, n in sorted(counts.items(), key=lambda kv: -kv[1]):
            flag = ""
            if site_of(u) in other_sites:
                flag = "FOREIGN：是别的 Job 的网站"
            elif u in other_postings:
                flag = "CHECK：是别的 Job 的岗位地址"
            rows.append([n, short(u, 100), flag])
        table(["次数", "页面地址", "标记"], rows)
        tab_urls = {}
        for turn in (runs.get(j) or {}).get("conversation") or []:
            for res in turn.get("results") or []:
                if res.get("name") != "browser_tabs":
                    continue
                s = iso_ms(res.get("startedAt"))
                if since and s is not None and s < since:
                    continue
                for u in URL_IN_TEXT.findall(res.get("text") or ""):
                    su = strip_url(u)
                    tab_urls[su] = tab_urls.get(su, 0) + 1
        if tab_urls:
            bad = [u for u in tab_urls if site_of(u) in other_sites or u in other_postings]
            print("  browser_tabs 返回里的网址 %d 个；属于别的 Job 的：%s" % (
                len(tab_urls), ", ".join(short(u, 80) for u in bad) if bad else "无"))


def report_browser(rec, base, app_log_path=None):
    print("\n## (c) CDP 连接（一条共享连接）\n")
    now, err = get_json(base, "/api/browser")
    seen = [(r["t"], r.get("state"), r.get("generation")) for r in rec
            if r.get("generation") is not None and (r.get("what") == "browser" or r.get("event") == "browser")]
    first = seen[0] if seen else None
    gens = sorted({g for _, _, g in seen if g})
    states = sorted({s for _, s, _ in seen if s})
    rows = []
    if first:
        rows.append(["watch 第一次", clock(first[0]), first[1], first[2]])
        rows.append(["watch 最后一次", clock(seen[-1][0]), seen[-1][1], seen[-1][2]])
    rows.append(["现在", clock(now_ms()), now and now.get("state"), now and now.get("generation")] if now
                else ["现在", clock(now_ms()), "读不到：" + err, "-"])
    table(["时刻", "时间", "state", "generation"], rows)
    if now and now.get("generation"):
        gens = sorted(set(gens) | {now.get("generation")})
    verdict = "PASS（只出现过一个连接代数）" if len(gens) == 1 and "disconnected" not in states \
        else ("未连上过" if not gens else "FAIL：代数 %s，出现过的状态 %s" % (gens, states))
    print("出现过的连接代数：%s；出现过的状态：%s → %s" % (gens or "-", states or "-", verdict))
    print("注意：代数只证明应用没有重连；Chrome 弹了几次允许以用户看到的为准。")
    if app_log_path:
        facts = app_log_facts(app_log_path)
        print("应用日志：" + "；".join("%s %d 次" % kv for kv in facts.items())
              + "（服务进程应为 1 次，会话断了应为 0 次；整个日志，不按 --since）")


def report_steering(jobs, runs, rec, apps):
    print("\n## (d) 插话（排队中 / 运行中）\n")
    print("对话里「用户插话：」开头（或包含它）的用户消息；只打印位置、标记和下一次模型回复调了哪些工具。")
    rows = []
    for j in jobs:
        conv = (runs.get(j) or {}).get("conversation") or []
        for i, turn in enumerate(conv):
            text = turn.get("text") or ""
            if turn.get("role") != "user":
                continue
            kind = "插话" if INSERTED in text else ("普通回复" if text.startswith(REPLIED) and MARKER.search(text)
                                                  else None)
            if not kind:
                continue
            before = sum(1 for t in conv[:i] if t.get("role") == "assistant")
            nxt = next((t for t in conv[i + 1:] if t.get("role") == "assistant"), None)
            tools = [c.get("name") for c in (nxt or {}).get("calls") or []]
            marker_in_args = any(MARKER.search(c.get("arguments") or "") for c in (nxt or {}).get("calls") or [])
            rows.append([j, kind, i, before, ",".join(sorted(set(MARKER.findall(text)))) or "-",
                         "无（还没回复）" if nxt is None else (",".join(tools) or "只回了文字"),
                         "是" if marker_in_args else "否"])
    table(["Job", "种类", "对话位置", "之前的模型回复数", "标记", "下一次模型回复的工具", "工具参数里有标记"], rows)
    print("「之前的模型回复数」=0：插话在这次运行第一次调模型之前就到了（排队中插话的样子）。"
          "\n「普通回复」= 发的时候已经不在跑，走的是普通回复，不算插话。")

    if rec:
        print("\n轮询看到的插话（status 是插话还没被取走时的状态）：")
        rows = []
        for j in jobs:
            polls = [r for r in rec if r.get("what") == "app" and r.get("job") == j]
            waiting = [r for r in polls if (r.get("inserted") or 0) > 0]
            if not waiting:
                rows.append([j, "-", "-", "-", "-"])
                continue
            statuses = sorted({r.get("status") for r in waiting})
            gone = next((r for r in polls if r["t"] > waiting[-1]["t"] and not r.get("inserted")), None)
            rows.append([j, clock(waiting[0]["t"]), ",".join(statuses),
                         clock(gone["t"]) if gone else "还没取走", gone.get("status") if gone else "-"])
        table(["Job", "第一次看到未取走", "当时的 status", "取走的时间", "取走后的 status"], rows)

    rows = []
    for j in jobs:
        plan = ((apps.get(j) or {}).get("plan")) or []
        marks = sorted({m for p in plan for m in MARKER.findall(p.get("text") or "")})
        rows.append([j, len(plan), ",".join(marks) or "-"])
    print("\n计划里出现的标记（模型按插话改了计划的证据）：")
    table(["Job", "计划项数", "计划里的标记"], rows)


def report_status(jobs, runs, logs, details, since):
    print("\n## (e) 状态，以及有没有走到已提交\n")
    rows = []
    bad = []
    for j in jobs:
        d = details.get(j) or {}
        job = d.get("job") or {}
        app = d.get("application") or {}
        run = runs.get(j) or {}
        guards = {}
        for e in logs.get(j, []):
            t = iso_ms(e.get("ts"))
            if e.get("kind") == "guard" and not (since and t is not None and t < since):
                guards[e.get("decision")] = guards.get(e.get("decision"), 0) + 1
        failed = sum(1 for turn in run.get("conversation") or [] for res in turn.get("results") or []
                     if (res.get("name") or "").startswith("browser_") and res.get("status") == "failed"
                     and not (since and (iso_ms(res.get("startedAt")) or 0) < since))
        phase = job.get("phase")
        prep = job.get("prepState") or app.get("prepState")
        submitted = (phase not in (None, "prep") or prep in SUBMITTED_PREP or job.get("appliedAt")
                     or job.get("pageLooksSubmitted") or ((run.get("lastPage") or {}).get("showsSubmitted")))
        if submitted:
            bad.append(j)
        rows.append([j, app.get("status") or run.get("status") or "-", phase or "-", prep or "-",
                     "是" if job.get("pageLooksSubmitted") else "否", job.get("appliedAt") or "-",
                     ",".join("%s×%d" % kv for kv in sorted(guards.items())) or "-", failed,
                     "FAIL 已提交" if submitted else "没有"])
    table(["Job", "application", "phase", "prep_state", "页面像已投递", "appliedAt", "守卫判断", "浏览器调用失败", "走到已提交"], rows)
    print("→ " + ("FAIL：%s 走到了已提交 / 页面像已投递，停下来查" % ", ".join(bad) if bad else "PASS：没有一个走到已提交"))


def cmd_report(args):
    storage = Path(args.storage).resolve()
    since = iso_ms(args.since) if args.since else None
    rec = load_rec(args.rec)
    runs, logs, details, apps = {}, {}, {}, {}
    for j in args.jobs:
        runs[j] = load_run(storage, j)
        project = (runs[j] or {}).get("project")
        logs[j] = load_runlog(storage, project, j)
        d, err = get_json(args.base, "/api/jobs/%s" % j)
        if d is None:
            print("提示：GET /api/jobs/%s 读不到（%s），(e) 只用文件" % (j, err))
        details[j] = d
        apps[j] = (d or {}).get("application")
        if runs[j] is None:
            print("提示：storage/runs/%s.json 不存在（这个 Job 还没开始过申请）" % j)
    print("# C011 冒烟观察  %s  Jobs: %s" % (datetime.now().strftime("%Y-%m-%d %H:%M:%S"), " ".join(args.jobs)))
    if since:
        print("只看 %s 之后" % args.since)
    app_log = app_log_intervals(args.app_log, since) if args.app_log else None
    report_overlap(args.jobs, rec, runs, app_log, since)
    report_tabs(args.jobs, runs, logs, since)
    report_browser(rec, args.base, args.app_log)
    report_steering(args.jobs, runs, rec, apps)
    report_status(args.jobs, runs, logs, details, since)


def main():
    p = argparse.ArgumentParser(description="C011 冒烟观察（只读，不打印填写的值）")
    p.add_argument("--base", default=DEFAULT_BASE, help="应用地址，默认 %(default)s")
    sub = p.add_subparsers(dest="cmd", required=True)
    w = sub.add_parser("watch", help="冒烟期间记录事件流和状态轮询")
    w.add_argument("--out", required=True, help="记录文件（jsonl，追加）")
    w.add_argument("--interval", type=float, default=1.0, help="轮询间隔秒，默认 1")
    w.add_argument("--duration", type=float, default=0, help="记录多少秒后自己结束；0 = 直到 Ctrl-C / SIGTERM")
    w.add_argument("jobs", nargs="+")
    r = sub.add_parser("report", help="打印 (a)–(e)")
    r.add_argument("--rec", help="watch 的记录文件")
    r.add_argument("--app-log", help="应用的控制台日志（启动时 tee 下来的文件）")
    r.add_argument("--storage", default=str(DEFAULT_STORAGE), help="storage 目录，默认仓库的 storage/")
    r.add_argument("--since", help="只看这之后的（ISO，例如 2026-10-24T15:00:00+08:00）；run-log 是追加的，旧运行也在里面")
    r.add_argument("jobs", nargs="+")
    args = p.parse_args()
    if args.cmd == "watch":
        cmd_watch(args)
    else:
        cmd_report(args)


if __name__ == "__main__":
    sys.exit(main())
