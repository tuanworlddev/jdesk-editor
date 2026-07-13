#!/usr/bin/env python3
import json, sys, time, urllib.request
port, token, out_dir = sys.argv[1], sys.argv[2], sys.argv[3]
tests = []
def ev(s):
    b = json.dumps({"window": "main", "script": s}).encode()
    r = urllib.request.Request(f"http://127.0.0.1:{port}/evaluate", data=b, headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(r, timeout=30).read()).get("result")
def rec(t, ok, d):
    tests.append({"id": t, "outcome": "PASS" if ok else "FAIL", "detail": str(d), "evidence": ["gate-results.json", "screenshots/undo.png"]})
    print(f"{'PASS' if ok else 'FAIL'} {t} — {d}")
ev('window.__editorDriver.openFile("f.txt")'); time.sleep(1)
ev('window.__editorDriver.type("EDIT\\n")'); time.sleep(0.5)
edited = ev('window.__editorProbe.activeText()')
rec("E2E-09a-edit", "EDIT" in (edited or ""), f"typed edit present: {edited!r}")
ev('window.__editorDriver.undo()'); time.sleep(0.5)
undone = ev('window.__editorProbe.activeText()')
rec("E2E-09b-undo", undone == "base\n", f"undo restored original (one undo group): {undone!r}")
ev('window.__editorDriver.redo()'); time.sleep(0.5)
redone = ev('window.__editorProbe.activeText()')
rec("E2E-09c-redo", "EDIT" in (redone or ""), f"redo restored edit: {redone!r}")
import os
ai = {"backend": "macos-wkwebview"}
json.dump(ai, open(os.path.join(out_dir, "app-info.json"), "w"), indent=2)
json.dump({"tests": tests, "appInfo": ai}, open(os.path.join(out_dir, "gate-results.json"), "w"), indent=2)
sys.exit(0 if all(t["outcome"] == "PASS" for t in tests) else 1)
