#!/usr/bin/env python3
"""insurance-claims E2E：通过业务应用 SSE 代理调用 DSH Agent，验证工具全链路。"""
import json, subprocess, sys

AGENT = "insurance-copilot"
URL = "http://127.0.0.1:18097/api/assistant/stream"

CASES = [
    ("T1 保单查询", "查一下保单列表，简要说明都有哪些保单", ["P2001", "P2005"]),
    ("T2 理赔核验", "保单 P2003 车险的车辆在停车场被剐蹭，维修费 800 元，帮我做一下理赔核验，能不能赔？简洁回答", ["P2003", "核验"]),
    ("T3 理赔登记", "帮保单 P2004 登记一笔意外险理赔：被保险人走路扭伤脚踝，门诊花费 600 元，已经过医院治疗，我已经确认无误，直接帮我登记理赔，告诉我案件号", ["C5", "意外"]),
    ("T4 理赔统计", "保险系统今天整体理赔情况怎么样？简洁回答", ["待审核", "赔付"]),
    ("T5 续保提醒", "有哪些保单快到期需要提醒续保？简洁回答", ["P2005", "续保"]),
]

def ask(message, timeout=170):
    payload = json.dumps({"message": message}, ensure_ascii=False)
    try:
        out = subprocess.run(
            ["curl", "-s", "--noproxy", "*", "-N", "-X", "POST", URL,
             "-H", "Content-Type: application/json", "-d", payload,
             "--max-time", str(timeout)],
            capture_output=True, text=True, timeout=timeout + 10).stdout
    except Exception as e:
        return "", f"curl 异常: {e}"
    text = []
    ev = ""
    for line in out.splitlines():
        line = line.rstrip("\r")
        if line.startswith("event:"):
            ev = line[6:].strip()
        elif line.startswith("data:"):
            s = line[5:].strip()
            if not s or s == "[DONE]" or ev != "chunk":
                continue
            try:
                j = json.loads(s)
                c = j.get("content", "")
                if c:
                    text.append(c)
            except Exception:
                pass
            ev = ""
    return "".join(text), out

def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    cases = CASES if not only else [c for c in CASES if c[0].startswith(only)]
    passed, failed = 0, []
    for name, q, keys in cases:
        reply, raw = ask(q)
        ok = all(k in reply for k in keys)
        print(f"[{'PASS' if ok else 'FAIL'}] {name}\n  Q: {q}\n  A: {reply[:200]}")
        if ok:
            passed += 1
        else:
            failed.append(name)
            if not reply:
                print(f"  raw 首行: {raw.splitlines()[:3] if raw else '(空)'}")
    print(f"\n===== insurance-claims E2E: {passed}/{len(cases)} PASS =====")

if __name__ == "__main__":
    main()
