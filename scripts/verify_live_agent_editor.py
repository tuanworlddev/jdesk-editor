#!/usr/bin/env python3
"""Verifies the capstone: a real agent edited a file OPEN in the running editor, and the change
shows live in Monaco AND on disk, having gone through our MCP tools. Writes gate-results.json."""
import json
import os
import sys

workspace, out_dir, claude_rc = sys.argv[1], sys.argv[2], int(sys.argv[3])
tests = []


def record(test_id, ok, detail):
    tests.append({"id": test_id, "outcome": "PASS" if ok else "FAIL", "detail": str(detail),
                  "evidence": ["gate-results.json", "claude-stream.jsonl", "live-editor-text.txt",
                               "screenshots/live-editor.png"]})
    print(f"{'PASS' if ok else 'FAIL'} {test_id} — {detail}")


# Claude's MCP tool calls.
tool_calls = []
stream = os.path.join(out_dir, "claude-stream.jsonl")
if os.path.exists(stream):
    for line in open(stream):
        line = line.strip()
        if not line:
            continue
        try:
            m = json.loads(line)
        except Exception:
            continue
        if m.get("type") == "assistant":
            for b in m.get("message", {}).get("content", []):
                if isinstance(b, dict) and b.get("type") == "tool_use":
                    tool_calls.append(b.get("name", ""))
editor_calls = [t for t in tool_calls if "jdesk_editor" in t]
record("LIVE-AGENT-01-mcp-tools", len(editor_calls) >= 1,
       f"Claude called {len(editor_calls)} editor MCP tool(s): {editor_calls}")

# The LIVE editor view reflects the agent's edit (read from the running Monaco model via /evaluate).
live_text = ""
p = os.path.join(out_dir, "live-editor-text.txt")
if os.path.exists(p):
    live_text = open(p).read()
record("LIVE-AGENT-02-live-monaco", "AGENT WAS HERE" in live_text,
       f"running editor Monaco content contains the agent's edit: {'AGENT WAS HERE' in live_text}")

# Disk reflects the saved edit.
disk = ""
dp = os.path.join(workspace, "src", "notes.txt")
if os.path.exists(dp):
    disk = open(dp).read()
record("LIVE-AGENT-03-disk", "AGENT WAS HERE" in disk,
       f"disk src/notes.txt contains the agent's edit: {'AGENT WAS HERE' in disk}")

app_info = {"backend": "macos-wkwebview", "claudeExit": claude_rc}
with open(os.path.join(out_dir, "app-info.json"), "w") as f:
    json.dump(app_info, f, indent=2)
with open(os.path.join(out_dir, "gate-results.json"), "w") as f:
    json.dump({"tests": tests, "appInfo": app_info, "toolCalls": tool_calls}, f, indent=2)

sys.exit(0 if all(t["outcome"] == "PASS" for t in tests) else 1)
