#!/usr/bin/env python3
"""Measures command-ack and warm file-open latency, writes docs/PERFORMANCE.md + gate-results.json.
Usage: perf_driver.py <port> <token> <outDir>"""
import json
import os
import platform
import subprocess
import sys
import time
import urllib.request

port, token, out_dir = sys.argv[1], sys.argv[2], sys.argv[3]
tests = []


def fmt(d, k):
    return f"{d[k]:.2f} ms" if d else "-"


def evaluate(script):
    body = json.dumps({"window": "main", "script": script}).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{port}/evaluate", data=body,
                                 headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read()).get("result")


def poll(kind, timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        res = evaluate('JSON.stringify(window.__perfResults)')
        parsed = json.loads(res) if res else {}
        if kind in parsed:
            return parsed[kind]
        time.sleep(0.3)
    return None


def record(tid, ok, detail):
    tests.append({"id": tid, "outcome": "PASS" if ok else "FAIL", "detail": str(detail),
                  "evidence": ["gate-results.json", "performance-report.md"]})
    print(f"{'PASS' if ok else 'FAIL'} {tid} — {detail}")


evaluate('window.__perf.commandRoundtrip(200)')
cmd = poll('commandRoundtrip')
evaluate('window.__perf.warmFileOpen("src/medium.txt", 100)')
opn = poll('warmFileOpen')

# Semantic/command ack target: p95 < 75 ms (loopback, no disk/process wait).
record("PERF-01-command-ack-p95", cmd is not None and cmd["p95"] < 75,
       f"command round-trip p50={cmd['p50']:.2f}ms p95={cmd['p95']:.2f}ms (target p95<75ms)" if cmd else "no data")
# Warm file-open is recorded (no hard gate per spec — "record actual").
record("PERF-02-warm-file-open-recorded", opn is not None,
       f"warm open p50={opn['p50']:.2f}ms p95={opn['p95']:.2f}ms" if opn else "no data")

# Hardware.
try:
    hw = subprocess.run(["system_profiler", "SPHardwareDataType"], capture_output=True, text=True, timeout=20).stdout
except Exception:
    hw = "unavailable"

report = f"""# Performance

Measured against the running editor (e2e-flavored build, production-mode Vite frontend) via the
automation endpoint. Latencies are client-observed loopback round-trips (they include the WebView
bridge and Java command dispatch). Spec §22.

## Hardware / environment

- OS: {platform.platform()}
- Machine: {[l.strip() for l in hw.splitlines() if 'Chip' in l or 'Model Name' in l or 'Memory' in l]}
- Build: e2e flavor (automation enabled), frontend built by `vite build` (minified).

## Results

| Metric | p50 | p95 | p99 | max | n | Target |
|---|---|---|---|---|---|---|
| Command ack (workspace.getState round-trip) | {fmt(cmd,'p50')} | {fmt(cmd,'p95')} | {fmt(cmd,'p99')} | {fmt(cmd,'max')} | {cmd['n'] if cmd else '-'} | p95 &lt; 75 ms |
| Warm file-open (doc.open, 2 KiB) | {fmt(opn,'p50')} | {fmt(opn,'p95')} | {fmt(opn,'p99')} | {fmt(opn,'max')} | {opn['n'] if opn else '-'} | record actual |

Terminal 10 MiB backpressure is covered by `TerminalOutputPumpTest` (delivers 10 MiB in full with
in-flight events bounded to the credit window).

## Limitations

- Single machine; thermals uncontrolled. Numbers are loopback client-observed (include bridge +
  dispatch), not isolated server compute. The pure-production package excludes the automation
  channel, so these are measured on the e2e build; the IPC path is identical.
"""
repo_root = os.getcwd()
os.makedirs(os.path.join(repo_root, "docs"), exist_ok=True)
with open(os.path.join(repo_root, "docs", "PERFORMANCE.md"), "w") as f:
    f.write(report)
# Also copy into the run dir so it can back the PASS as run-produced evidence.
with open(os.path.join(out_dir, "performance-report.md"), "w") as f:
    f.write(report)

app_info = {"backend": "macos-wkwebview"}
with open(os.path.join(out_dir, "app-info.json"), "w") as f:
    json.dump(app_info, f, indent=2)
with open(os.path.join(out_dir, "gate-results.json"), "w") as f:
    json.dump({"tests": tests, "appInfo": app_info, "commandRoundtrip": cmd, "warmFileOpen": opn}, f, indent=2)
sys.exit(0 if all(t["outcome"] == "PASS" for t in tests) else 1)
