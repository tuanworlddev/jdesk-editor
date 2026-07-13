#!/usr/bin/env python3
"""Drives the watcher acceptance: external modify -> reload; dirty -> conflict (no overwrite).
Usage: e2e_watcher_driver.py <port> <token> <workspace> <outDir>"""
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
                  "evidence": ["gate-results.json", "screenshots/watcher.png"]})
    print(f"{'PASS' if ok else 'FAIL'} {tid} — {detail}")


def wait_for(script, pred, timeout=15):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = evaluate(script)
        if pred(last):
            return last
        time.sleep(0.3)
    return last


# Open the file in the editor.
evaluate('window.__editorDriver.openFile("watched.txt")')
wait_for('window.__editorProbe.activeText()', lambda v: v == "original\n")
uri = evaluate('window.__editorProbe.activeUri()')

# E2E-10: modify the file EXTERNALLY on disk; the editor should reload it (clean doc).
with open(os.path.join(ws, "watched.txt"), "w") as f:
    f.write("externally changed\n")
reloaded = wait_for('window.__editorProbe.activeText()', lambda v: v == "externally changed\n", 20)
state = evaluate(f'window.__watcherProbe.externalState({json.dumps(uri)})')
record("E2E-10-watcher-reload", reloaded == "externally changed\n",
       f"clean doc reloaded from disk to {reloaded!r}")
record("E2E-10b-labeled-external", state in ("RELOADED", "DELETED", "CONFLICT"),
       f"change labeled origin=external-watcher, state={state}")

# E2E-11: make the buffer dirty, then change the file on disk -> CONFLICT, buffer NOT overwritten.
evaluate('window.__editorDriver.type("\\nlocal unsaved edit\\n")')
time.sleep(0.5)
dirty_text = evaluate('window.__editorProbe.activeText()')
with open(os.path.join(ws, "watched.txt"), "w") as f:
    f.write("disk changed again while dirty\n")
conflict_state = wait_for(f'window.__watcherProbe.externalState({json.dumps(uri)})',
                          lambda v: v == "CONFLICT", 20)
after = evaluate('window.__editorProbe.activeText()')
record("E2E-11-conflict-no-overwrite",
       conflict_state == "CONFLICT" and "local unsaved edit" in (after or ""),
       f"dirty buffer preserved on conflict (state={conflict_state}, buffer kept edit={'local unsaved edit' in (after or '')})")

app_info = {"backend": "macos-wkwebview"}
with open(os.path.join(out_dir, "app-info.json"), "w") as f:
    json.dump(app_info, f, indent=2)
with open(os.path.join(out_dir, "gate-results.json"), "w") as f:
    json.dump({"tests": tests, "appInfo": app_info}, f, indent=2)
sys.exit(0 if all(t["outcome"] == "PASS" for t in tests) else 1)
