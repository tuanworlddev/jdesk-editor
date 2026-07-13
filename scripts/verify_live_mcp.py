#!/usr/bin/env python3
"""Verifies the live Claude-over-MCP run: Claude must have actually called our editor MCP tools,
and the file must exist on disk with the expected content. Writes gate-results.json."""
import hashlib
import json
import os
import sys

workspace, out_dir, claude_rc = sys.argv[1], sys.argv[2], int(sys.argv[3])
tests = []


def record(test_id, ok, detail):
    tests.append({"id": test_id, "outcome": "PASS" if ok else "FAIL", "detail": str(detail),
                  "evidence": ["gate-results.json", "claude-stream.jsonl"]})
    print(f"{'PASS' if ok else 'FAIL'} {test_id} — {detail}")


# Parse the Claude stream for MCP tool_use calls naming our editor tools.
tool_calls = []
result_ok = False
stream_path = os.path.join(out_dir, "claude-stream.jsonl")
if os.path.exists(stream_path):
    for line in open(stream_path):
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except Exception:
            continue
        if msg.get("type") == "assistant":
            for block in msg.get("message", {}).get("content", []):
                if isinstance(block, dict) and block.get("type") == "tool_use":
                    tool_calls.append(block.get("name", ""))
        if msg.get("type") == "result":
            result_ok = msg.get("subtype") == "success" and not msg.get("is_error")

editor_tool_calls = [t for t in tool_calls if "jdesk_editor" in t]
record("LIVE-CLAUDE-01-tools-called",
       len(editor_tool_calls) >= 1,
       f"Claude called {len(editor_tool_calls)} jdesk_editor MCP tool(s): {editor_tool_calls}")

# The file must exist on disk (proof the tools actually operated the editor).
disk_path = os.path.join(workspace, "src", "hello.txt")
exists = os.path.exists(disk_path)
content = open(disk_path).read() if exists else ""
record("LIVE-CLAUDE-02-file-on-disk", exists and "Hello from Claude" in content,
       f"src/hello.txt exists={exists}, content={content!r}")

# Claude reached a successful result.
record("LIVE-CLAUDE-03-turn-completed", result_ok or claude_rc == 0,
       f"claude result success={result_ok}, exit={claude_rc}")

app_info = {"backend": "mcp-loopback-http", "claudeExit": claude_rc}
with open(os.path.join(out_dir, "app-info.json"), "w") as f:
    json.dump(app_info, f, indent=2)
with open(os.path.join(out_dir, "gate-results.json"), "w") as f:
    json.dump({"tests": tests, "appInfo": app_info,
               "toolCalls": tool_calls}, f, indent=2)

sys.exit(0 if all(t["outcome"] == "PASS" for t in tests) else 1)
