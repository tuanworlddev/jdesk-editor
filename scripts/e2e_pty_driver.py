#!/usr/bin/env python3
"""Drives the running editor's real PTY through the MCP terminal tools and verifies the marker
output and exit code. Writes gate-results.json. Usage: e2e_pty_driver.py <mcpUrl> <auth> <outDir>"""
import json
import sys
import time
import urllib.request

mcp_url, auth, out_dir = sys.argv[1], sys.argv[2], sys.argv[3]
tests = []
_id = [0]


def rpc(method, params=None):
    _id[0] += 1
    body = {"jsonrpc": "2.0", "id": _id[0], "method": method}
    if params is not None:
        body["params"] = params
    req = urllib.request.Request(mcp_url, data=json.dumps(body).encode(),
                                 headers={"Authorization": auth, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())


def call_tool(name, arguments):
    result = rpc("tools/call", {"name": name, "arguments": arguments}).get("result", {})
    if result.get("isError"):
        return {"_error": result.get("structuredContent", {})}
    return result.get("structuredContent", {})


def record(test_id, ok, detail):
    tests.append({"id": test_id, "outcome": "PASS" if ok else "FAIL", "detail": str(detail),
                  "evidence": ["gate-results.json", "screenshots/pty.png"]})
    print(f"{'PASS' if ok else 'FAIL'} {test_id} — {detail}")


rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                   "clientInfo": {"name": "pty-e2e", "version": "1"}})

# Open a real PTY.
opened = call_tool("terminal_open", {"cols": 80, "rows": 24})
term_id = opened.get("terminalId")
record("PTY-E2E-01-open", bool(term_id), f"terminal opened: {term_id}")

if term_id:
    # Run a command with a unique marker and a specific exit code.
    call_tool("terminal_write", {"terminalId": term_id, "data": "printf 'PTY-MARK-4242\\n'; exit 7\n"})

    # Poll for output + exit code.
    output = ""
    exit_code = None
    for _ in range(40):
        read = call_tool("terminal_read", {"terminalId": term_id})
        output += read.get("output", "")
        if "exitCode" in read:
            exit_code = read["exitCode"]
        if "PTY-MARK-4242" in output and exit_code is not None:
            break
        time.sleep(0.25)

    record("PTY-E2E-02-output", "PTY-MARK-4242" in output,
           f"marker present in real PTY output: {'PTY-MARK-4242' in output}")
    record("PTY-E2E-03-exit-code", exit_code == 7, f"exit code = {exit_code} (expected 7)")
    call_tool("terminal_close", {"terminalId": term_id})

app_info = {"backend": "macos-wkwebview"}
with open(f"{out_dir}/app-info.json", "w") as f:
    json.dump(app_info, f, indent=2)
with open(f"{out_dir}/gate-results.json", "w") as f:
    json.dump({"tests": tests, "appInfo": app_info}, f, indent=2)

sys.exit(0 if all(t["outcome"] == "PASS" for t in tests) else 1)
