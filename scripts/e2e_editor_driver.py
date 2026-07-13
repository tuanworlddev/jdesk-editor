#!/usr/bin/env python3
"""Phase-1 native E2E driver. Drives the REAL editor through the automation /evaluate endpoint and
verifies disk bytes. Writes gate-results.json (ingested by evidence-cli app-run) into the run dir.

Usage: e2e_editor_driver.py <port> <token> <workspace> <out-dir>
"""
import hashlib
import json
import sys
import time
import urllib.request

port, token, workspace, out_dir = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
tests = []


def evaluate(script):
    body = json.dumps({"window": "main", "script": script}).encode()
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}/evaluate", data=body,
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        parsed = json.loads(resp.read())
    value = parsed.get("result", parsed.get("value"))
    # /evaluate returns the JS value; strings that hold JSON are returned as-is.
    return value


def record(test_id, ok, detail):
    tests.append({"id": test_id, "outcome": "PASS" if ok else "FAIL", "detail": str(detail),
                  "evidence": ["gate-results.json", "console.json", "screenshots/editor.png"]})
    print(f"{'PASS' if ok else 'FAIL'} {test_id} — {detail}")


def wait_for(script, predicate, timeout=15):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = evaluate(script)
        if predicate(last):
            return last
        time.sleep(0.3)
    return last


# E2E-01: workspace loaded and Explorer populated with stable semantic ids.
rows = evaluate('JSON.stringify(Array.from(document.querySelectorAll("[data-semantic-id]"))'
                '.map(e=>e.getAttribute("data-semantic-id")))')
row_ids = json.loads(rows) if rows else []
record("E2E-01-workspace", "file:README.md" in row_ids and "folder:src" in row_ids,
       f"explorer ids: {row_ids}")

# E2E-02: open a file through the real code path; tab + model appear.
evaluate('window.__editorDriver.openFile("README.md")')
active = wait_for('window.__editorProbe.activeText()', lambda v: v == "# Demo\n")
record("E2E-02-open", active == "# Demo\n", f"opened README.md, content={active!r}")

# E2E-03: edit through Monaco; the delta auto-flushes to Java and the version advances.
# (/evaluate cannot await Promises, so we fire the edit and poll the synchronous version getter.)
evaluate('window.__editorDriver.type("\\nedited by e2e\\n")')
version = wait_for('window.__editorProbe.javaVersion()', lambda v: isinstance(v, (int, float)) and v > 1)
text_after = evaluate('window.__editorProbe.activeText()')
record("E2E-03-edit", text_after == "# Demo\n\nedited by e2e\n" and isinstance(version, (int, float)) and version > 1,
       f"post-edit java version={version}, text={text_after!r}")

# E2E-04: dirty flag reflects unsaved edit.
dirty = evaluate('window.__editorProbe.activeDirty()')
record("E2E-04-dirty", dirty is True, f"dirty={dirty}")

# E2E-05: save writes exact bytes to disk with the expected hash.
evaluate('window.__editorDriver.save()')
time.sleep(0.6)
import os
disk_path = os.path.join(workspace, "README.md")
disk_bytes = open(disk_path, "rb").read()
expected = "# Demo\n\nedited by e2e\n".encode()
disk_hash = hashlib.sha256(disk_bytes).hexdigest()
expected_hash = hashlib.sha256(expected).hexdigest()
record("E2E-05-save", disk_bytes == expected,
       f"disk sha256={disk_hash[:12]} expected={expected_hash[:12]} match={disk_bytes == expected}")

# E2E-06: after save the dirty flag clears.
dirty_after = wait_for('window.__editorProbe.activeDirty()', lambda v: v is False, timeout=5)
record("E2E-06-clean-after-save", dirty_after is False, f"dirty after save={dirty_after}")

# app-info + results.
ua = evaluate('navigator.userAgent')
backend = "macos-wkwebview" if isinstance(ua, str) and "AppleWebKit" in ua else "unknown"
app_info = {"backend": backend, "userAgent": ua}
with open(os.path.join(out_dir, "app-info.json"), "w") as f:
    json.dump(app_info, f, indent=2)
with open(os.path.join(out_dir, "gate-results.json"), "w") as f:
    json.dump({"tests": tests, "appInfo": app_info}, f, indent=2)

all_pass = all(t["outcome"] == "PASS" for t in tests)
sys.exit(0 if all_pass else 1)
