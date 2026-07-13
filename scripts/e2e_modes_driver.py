#!/usr/bin/env python3
"""Applies the same staged edit in INSTANT/LIVE/CINEMATIC and asserts byte-identical final hashes
(spec §12.4). Usage: e2e_modes_driver.py <port> <token> <outDir>"""
import hashlib
import json
import sys
import time
import urllib.request

port, token, out_dir = sys.argv[1], sys.argv[2], sys.argv[3]
tests = []


def evaluate(script):
    body = json.dumps({"window": "main", "script": script}).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{port}/evaluate", data=body,
                                 headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read()).get("result")


def record(tid, ok, detail):
    tests.append({"id": tid, "outcome": "PASS" if ok else "FAIL", "detail": str(detail),
                  "evidence": ["gate-results.json", "screenshots/modes.png"]})
    print(f"{'PASS' if ok else 'FAIL'} {tid} — {detail}")


# A non-trivial base + a multi-part edit (insert + replace) that exercises the streaming paths.
base = "function greet() {\n  return 'hi';\n}\n"
edits = [
    {"startLine": 1, "startColumn": 1, "endLine": 1, "endColumn": 1, "text": "// header comment\n"},
    {"startLine": 2, "startColumn": 11, "endLine": 2, "endColumn": 15, "text": "'hello, world from all three modes'"},
]
edits_json = json.dumps(edits)

# Expected final content computed independently in Python (the authoritative reference).
def apply(base_text, es):
    # Apply in original coordinates, last-to-first by (line,col).
    lines_offsets = [0]
    for i, ch in enumerate(base_text):
        if ch == "\n":
            lines_offsets.append(i + 1)
    def off(line, col):
        return lines_offsets[line - 1] + (col - 1)
    resolved = sorted(([off(e["startLine"], e["startColumn"]), off(e["endLine"], e["endColumn"]), e["text"]] for e in es),
                      key=lambda r: r[0])
    result = base_text
    for start, end, text in reversed(resolved):
        result = result[:start] + text + result[end:]
    return result

expected = apply(base, edits)
expected_hash = hashlib.sha256(expected.encode()).hexdigest()

hashes = {}
for mode in ("INSTANT", "LIVE", "CINEMATIC"):
    evaluate(f'window.__editorDriver.applyMode({json.dumps(base)}, {json.dumps(edits_json)}, {json.dumps(mode)})')
    # Poll the recorded result hash for this mode.
    h = None
    for _ in range(60):
        results = evaluate('JSON.stringify(window.__modeResults)')
        parsed = json.loads(results) if results else {}
        if mode in parsed:
            h = parsed[mode]
            break
        time.sleep(0.25)
    hashes[mode] = h
    record(f"MODE-{mode}", h == expected_hash, f"{mode} final hash={str(h)[:12]} expected={expected_hash[:12]}")

all_equal = len(set(hashes.values())) == 1 and None not in hashes.values()
record("MODE-byte-identical", all_equal,
       f"all three modes produced identical final content: {all_equal} ({list(hashes.values())[0] if all_equal else hashes})")

app_info = {"backend": "macos-wkwebview"}
with open(f"{out_dir}/app-info.json", "w") as f:
    json.dump(app_info, f, indent=2)
with open(f"{out_dir}/gate-results.json", "w") as f:
    json.dump({"tests": tests, "appInfo": app_info, "hashes": hashes}, f, indent=2)
sys.exit(0 if all(t["outcome"] == "PASS" for t in tests) else 1)
