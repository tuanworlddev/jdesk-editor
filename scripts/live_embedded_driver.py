#!/usr/bin/env python3
"""Drives the embedded-agent acceptance: the running editor spawns a managed Claude session that
edits a file, appearing live. Usage: live_embedded_driver.py <port> <token> <workspace> <outDir>"""
import json
import os
import sys
import time
import urllib.request

port, token, ws, out_dir = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
tests = []


def evaluate(script):
    body = json.dumps({"window": "main", "script": script}).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{port}/evaluate", data=body,
                                 headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read()).get("result")


def record(tid, ok, detail):
    tests.append({"id": tid, "outcome": "PASS" if ok else "FAIL", "detail": str(detail),
                  "evidence": ["gate-results.json", "screenshots/embedded.png"]})
    print(f"{'PASS' if ok else 'FAIL'} {tid} — {detail}")


# Open the target file so the agent's edit must appear live.
evaluate('window.__editorDriver.openFile("src/target.txt")')
time.sleep(1)

# The RUNNING EDITOR spawns and manages the Claude session (embedded lifecycle).
prompt = ("Use the jdesk_editor MCP tools ONLY. Call editor_apply_workspace_edit on relPath "
          "'src/target.txt' to insert 'EMBEDDED AGENT EDIT\\n' at line 1 column 1, then editor_save "
          "on 'src/target.txt'. Then reply DONE.")
evaluate(f'window.__agentDriver.start({json.dumps(prompt)})')

# Poll the managed session until done (agent runs a real turn — allow generous time).
status = None
for _ in range(240):
    evaluate('window.__agentDriver.refreshStatus()')
    time.sleep(1)
    raw = evaluate('JSON.stringify(window.__agentProbe.status())')
    status = json.loads(raw) if raw and raw != 'null' else None
    if status and status.get("done"):
        break

record("LIVE-EMBEDDED-01-managed-session", status is not None,
       f"editor started a managed Claude session: {status is not None}")
tool_calls = (status or {}).get("toolCalls", [])
editor_calls = [t for t in tool_calls if "jdesk_editor" in t]
record("LIVE-EMBEDDED-02-mcp-tools", len(editor_calls) >= 1,
       f"embedded agent called editor MCP tools: {editor_calls}")

# The live editor reflects the agent's edit.
live_text = evaluate('window.__editorProbe.activeText()')
record("LIVE-EMBEDDED-03-live-editor", "EMBEDDED AGENT EDIT" in (live_text or ""),
       f"running editor shows the embedded agent's edit: {'EMBEDDED AGENT EDIT' in (live_text or '')}")

# Disk reflects it.
disk = open(os.path.join(ws, "src", "target.txt")).read() if os.path.exists(os.path.join(ws, "src", "target.txt")) else ""
record("LIVE-EMBEDDED-04-disk", "EMBEDDED AGENT EDIT" in disk,
       f"disk reflects the embedded agent's saved edit: {'EMBEDDED AGENT EDIT' in disk}")

app_info = {"backend": "macos-wkwebview"}
with open(os.path.join(out_dir, "app-info.json"), "w") as f:
    json.dump(app_info, f, indent=2)
with open(os.path.join(out_dir, "gate-results.json"), "w") as f:
    json.dump({"tests": tests, "appInfo": app_info, "agentStatus": status}, f, indent=2)
sys.exit(0 if all(t["outcome"] == "PASS" for t in tests) else 1)
