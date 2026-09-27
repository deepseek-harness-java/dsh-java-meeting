#!/usr/bin/env python3
"""Meeting Digest E2E: app API + AI stream tool calls.

Usage: python3 meeting_digest_e2e.py
Prereq: app on 127.0.0.1:8104, harness on 127.0.0.1:8090,
        plugin dsh-java-meeting-plugin installed & activated.
"""
import json
import sys
import time
import urllib.request

APP = "http://127.0.0.1:8104"
TRANSCRIPT = (
    "主持人: 本周会三件事，支付对账、新首页灰度、Q4 排期。\n"
    "张三: 支付对账脚本本周五前要完成，由王五负责跟进，目前差一个渠道的账单格式。\n"
    "李四: 新首页灰度方案我不同意，有分歧，用户路径变长了，担心转化率下降，需要 A/B 数据支撑。\n"
    "王五: 收到，对账脚本我来写，下周三前输出第一版，另外让我准备渠道账单映射文档。\n"
    "主持人: 决定新首页灰度先小流量 5% 观察，下周看数据再定。\n"
    "赵六: Q4 排期确定，大促活动 11 月 1 日上线，需要让陈七准备活动页素材，月底前完成初稿。\n"
    "李四: 同意小流量方案，但保留意见，转化率如果跌 2% 就回滚。\n"
    "主持人: 好，散会。"
)
results = []


def call(path, payload=None, method=None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(APP + path, data=data, method=method or ("POST" if data else "GET"),
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")


def stream_once(agent_id, message, meeting_id):
    body = json.dumps({"agentId": agent_id, "message": message, "meetingId": meeting_id}).encode()
    req = urllib.request.Request(APP + "/api/assistant/stream", data=body, method="POST",
                                 headers={"Content-Type": "application/json"})
    content, tools, events = "", [], []
    try:
        with urllib.request.urlopen(req, timeout=200) as resp:
            event, data_buf = "message", []
            for raw in resp.read().decode("utf-8", "ignore").splitlines() + [""]:
                if raw.startswith("event:"):
                    event = raw[6:].strip()
                elif raw.startswith("data:"):
                    data_buf.append(raw[5:].strip())
                elif not raw and data_buf:
                    try:
                        payload = json.loads("\n".join(data_buf))
                        events.append(event)
                        if event == "chunk":
                            content += payload.get("content", "")
                        if event == "step_break" and payload.get("toolName"):
                            tools.append(payload["toolName"])
                    except json.JSONDecodeError:
                        pass
                    event, data_buf = "message", []
    except Exception as e:  # noqa: BLE001
        content += f"\nSTREAM-ERROR: {e}"
    return content, sorted(set(tools)), events


def case(name):
    def deco(fn):
        def wrapped():
            start = time.time()
            try:
                detail = fn()
                results.append((name, True, detail, time.time() - start))
                print(f"PASS  {name}  ({time.time()-start:.1f}s)  {detail}")
            except AssertionError as e:
                results.append((name, False, str(e), time.time() - start))
                print(f"FAIL  {name}  ({time.time()-start:.1f}s)  {e}")
            except Exception as e:  # noqa: BLE001
                results.append((name, False, f"{type(e).__name__}: {e}", time.time() - start))
                print(f"FAIL  {name}  ({time.time()-start:.1f}s)  {type(e).__name__}: {e}")
        return wrapped
    return deco


MEETING_ID = None


@case("T0 摄入转录")
def t0():
    global MEETING_ID
    status, body = call("/api/meetings", {"title": "E2E 产品周会", "text": TRANSCRIPT})
    assert status == 200, f"ingest {status}: {body}"
    MEETING_ID = body["id"]
    assert body.get("chars", 0) >= 200, f"chars={body.get('chars')}"
    return f"id={MEETING_ID} chars={body['chars']}"


@case("T1 待办提取")
def t1():
    status, body = call(f"/api/meetings/{MEETING_ID}/actions", {})
    assert status == 200, f"actions {status}: {body}"
    owners = {a.get("owner") for a in body.get("actions", [])}
    assert any("王五" in str(o) or "陈七" in str(o) for o in owners), f"owners={owners}"
    assert body["actionCount"] >= 2, f"count={body['actionCount']}"
    return f"actions={body['actionCount']} owners={owners}"


@case("T2 决议提取")
def t2():
    status, body = call(f"/api/meetings/{MEETING_ID}/decisions", {})
    assert status == 200, f"decisions {status}: {body}"
    assert body["decisionCount"] >= 1, f"count={body['decisionCount']}"
    joined = json.dumps(body, ensure_ascii=False)
    assert "小流量" in joined or "灰度" in joined, "gray-release decision missing"
    return f"decisions={body['decisionCount']}"


@case("T3 分歧与跟进清单")
def t3():
    status, body = call(f"/api/meetings/{MEETING_ID}/followups", {})
    assert status == 200, f"followups {status}: {body}"
    assert body["concernCount"] >= 1, f"concerns={body['concernCount']}"
    joined = json.dumps(body, ensure_ascii=False)
    assert "李四" in joined, "李四's concern missing"
    assert body.get("byOwner"), "byOwner missing"
    return f"actions={body['actions']} concerns={body['concernCount']} owners={len(body['byOwner'])}"


@case("T4 话题切分")
def t4():
    status, body = call(f"/api/meetings/{MEETING_ID}/topics", {})
    assert status == 200, f"topics {status}: {body}"
    assert body["topicCount"] >= 3, f"topics={body['topicCount']}"
    return f"topics={body['topicCount']}"


@case("T5 AI 对话调用会议工具")
def t5():
    content, tools, events = stream_once("e2e-mtg-t5", "这个会有几条待办？谁的任务最多？有分歧吗？", MEETING_ID)
    assert any("mtg_" in t for t in tools), f"no mtg tool called, tools={tools}"
    assert "chunk" in events, "no chunk events"
    assert len(content) > 20, f"content too short: {content[:80]}"
    return f"tools={tools} chars={len(content)}"


@case("T6 太短文本拒绝")
def t6():
    status, body = call("/api/meetings", {"title": "x", "text": "太短"})
    assert status == 400, f"should reject: {status} {body}"
    return "short text rejected with 400"


def main():
    t0(); t1(); t2(); t3(); t4(); t5(); t6()
    passed = sum(1 for _, ok, _, _ in results if ok)
    print(f"\n{passed}/{len(results)} PASS")
    sys.exit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
