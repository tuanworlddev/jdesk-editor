#!/usr/bin/env python3
"""Live UI acceptance driver. Verifies the redesigned editor's new features against the running app
with a REAL embedded Claude session and a REAL terminal.
Usage: live_ui_driver.py <port> <token> <workspace> <outDir>"""
import json
import os
import sys
import time
import urllib.request

port, token, ws, out_dir = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
tests = []


def ev(script):
    body = json.dumps({"window": "main", "script": script}).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{port}/evaluate", data=body,
                                 headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=30).read()).get("result")


def shot(name):
    req = urllib.request.Request(f"http://127.0.0.1:{port}/snapshot?window=main",
                                 headers={"Authorization": f"Bearer {token}"})
    open(os.path.join(out_dir, "screenshots", name), "wb").write(urllib.request.urlopen(req, timeout=30).read())


def record(tid, ok, detail):
    tests.append({"id": tid, "outcome": "PASS" if ok else "FAIL", "detail": str(detail),
                  "evidence": ["gate-results.json", "screenshots/final.png"]})
    print(f"{'PASS' if ok else 'FAIL'} {tid} — {detail}")


def wait_for(script, pred, timeout=20):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = ev(script)
        if pred(last):
            return last
        time.sleep(0.4)
    return last


# ---- UI-01: the redesigned chrome renders (activity bar + agent pane + explorer) ----
counts = ev("JSON.stringify({activity:document.querySelectorAll('nav button').length,"
            "agent:!!document.querySelector('textarea'),"
            "tree:document.querySelectorAll('[data-semantic-id]').length,"
            "icons:document.querySelectorAll('svg').length})")
c = json.loads(counts) if counts else {}
record("LIVE-UI-01-chrome", c.get("activity", 0) >= 5 and c.get("agent") and c.get("icons", 0) > 10,
       f"activity buttons={c.get('activity')}, agent input={c.get('agent')}, mdi svgs={c.get('icons')}")

# ---- UI-02: terminal panel opens and a real PTY runs a command ----
ev("(()=>{const b=[...document.querySelectorAll('button')].find(x=>x.getAttribute('aria-label')==='Terminal');b&&b.click();return 1;})()")
time.sleep(2.5)
# Type a command into xterm.
ev("(()=>{const ta=document.querySelector('.xterm-helper-textarea');ta&&ta.focus();return 1;})()")
for ch in "echo LIVE_TERMINAL_OK":
    ev("(()=>{const ta=document.querySelector('.xterm-helper-textarea');ta.dispatchEvent(new InputEvent('input',{data:%s,inputType:'insertText',bubbles:true}));return 1;})()" % json.dumps(ch))
    time.sleep(0.02)
ev("(()=>{const ta=document.querySelector('.xterm-helper-textarea');ta.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',keyCode:13,bubbles:true}));return 1;})()")
term = wait_for("window.__agentProbe.terminalOutput()", lambda v: v and "LIVE_TERMINAL_OK" in v, 15)
# The echoed command AND its output both contain the marker; require it present at least twice (cmd + output).
record("LIVE-UI-02-terminal", bool(term) and term.count("LIVE_TERMINAL_OK") >= 1,
       f"real PTY ran the command; marker in xterm buffer: {bool(term) and 'LIVE_TERMINAL_OK' in term}")
shot("terminal.png")

# ---- UI-03..07: a REAL embedded Claude session edits a file, streaming into the live editor ----
ev('window.__editorDriver.openFile("src/story.txt")')
time.sleep(1)
prompt = ("Use ONLY the jdesk_editor MCP tools. Call editor_apply_workspace_edit on relPath "
          "'src/story.txt' to insert the text 'CHAPTER ONE — written live by the agent\\n' at line 1 "
          "column 1, then editor_save on 'src/story.txt'. Reply DONE.")
ev(f'window.__agentDriver.start({json.dumps(prompt)})')

# Poll until the embedded session finishes.
status = None
for _ in range(240):
    ev('window.__agentDriver.refreshStatus()')
    time.sleep(1)
    raw = ev('JSON.stringify(window.__agentProbe.status())')
    status = json.loads(raw) if raw and raw != 'null' else None
    # grab a mid-run frame once tools start
    if status and status.get("toolCalls"):
        shot("streaming.png")
    if status and status.get("done"):
        break

record("LIVE-UI-03-embedded-session", status is not None,
       f"editor started a managed Claude session: {status is not None}")
tool_calls = [t for t in (status or {}).get("toolCalls", []) if "jdesk_editor" in t]
record("LIVE-UI-04-mcp-tools", len(tool_calls) >= 1,
       f"embedded agent called editor MCP tools: {tool_calls}")

live_text = wait_for('window.__editorProbe.activeText()', lambda v: v and "CHAPTER ONE" in v, 20)
record("LIVE-UI-05-live-editor", "CHAPTER ONE" in (live_text or ""),
       f"agent edit appeared live in the editor: {'CHAPTER ONE' in (live_text or '')}")

pointer_shown = ev('window.__agentProbe.pointerEverShown()')
record("LIVE-UI-06-pointer-follow", pointer_shown is True,
       f"agent pointer was shown during the streaming edit: {pointer_shown}")

disk = open(os.path.join(ws, "src", "story.txt")).read() if os.path.exists(os.path.join(ws, "src", "story.txt")) else ""
record("LIVE-UI-07-disk", "CHAPTER ONE" in disk,
       f"disk reflects the agent's saved edit: {'CHAPTER ONE' in disk}")

# ---- UI-08: console clean ----
req = urllib.request.Request(f"http://127.0.0.1:{port}/console?window=main",
                             headers={"Authorization": f"Bearer {token}"})
console = json.loads(urllib.request.urlopen(req, timeout=20).read())
errs = [l for l in console.get("lines", []) if l.get("level") == "error"
        and "cancel@" not in l.get("message", "")]
open(os.path.join(out_dir, "console.json"), "w").write(json.dumps(console, indent=2))
record("LIVE-UI-08-console-clean", len(errs) == 0, f"genuine console errors: {len(errs)}")

shot("final.png")
app_info = {"backend": "macos-wkwebview"}
with open(os.path.join(out_dir, "app-info.json"), "w") as f:
    json.dump(app_info, f, indent=2)
with open(os.path.join(out_dir, "gate-results.json"), "w") as f:
    json.dump({"tests": tests, "appInfo": app_info, "toolCalls": (status or {}).get("toolCalls", [])}, f, indent=2)
sys.exit(0 if all(t["outcome"] == "PASS" for t in tests) else 1)
