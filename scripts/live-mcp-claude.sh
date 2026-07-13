#!/usr/bin/env bash
# LIVE agent-native proof: the REAL authenticated Claude Code CLI connects to the editor's MCP
# server and drives editor tools to create + edit + save a file. Verifies (a) Claude actually
# called our editor_* MCP tools (from its stream-json tool_use events) and (b) the bytes landed on
# disk. Writes gate-results.json + evidence into $JDESK_EDITOR_RUN_DIR. Exits 0 iff both hold;
# BLOCKED (exit 3) if claude is unavailable.
set -uo pipefail
cd "$(dirname "$0")/.."

OUT="${JDESK_EDITOR_RUN_DIR:-build/live-mcp-out}"
mkdir -p "$OUT"

command -v claude >/dev/null 2>&1 || {
  python3 scripts/write_gate_fail.py "$OUT" LIVE-CLAUDE-MCP "claude CLI not found (BLOCKED)"; exit 3; }

WS="$(mktemp -d)/proj"
mkdir -p "$WS"
printf '{"name":"demo"}\n' > "$WS/package.json"
CONFIG="$(mktemp).json"
DISCOVERY="$(mktemp).json"

# Start the MCP server over the workspace.
./gradlew -q :agent-mcp:mcpServe -PmcpArgs="$WS $CONFIG $DISCOVERY" </dev/null >"$OUT/mcp-server.log" 2>&1 &
SERVE_PID=$!
cleanup() { kill "$SERVE_PID" 2>/dev/null; pkill -f McpLauncher 2>/dev/null; }
trap cleanup EXIT

# Wait for the server to be ready (config file written).
for _ in $(seq 1 60); do
  [ -s "$CONFIG" ] && break
  kill -0 "$SERVE_PID" 2>/dev/null || break
  sleep 1
done
[ -s "$CONFIG" ] || { python3 scripts/write_gate_fail.py "$OUT" LIVE-CLAUDE-MCP "MCP server did not start"; exit 1; }
cp "$CONFIG" "$OUT/mcp-config.redacted.json.orig" 2>/dev/null || true

PROMPT="You are connected to the JDesk Editor via the jdesk_editor MCP server. Using ONLY those \
tools, do exactly this: call file_create for relPath \"src/hello.txt\"; then editor_apply_workspace_edit \
on \"src/hello.txt\" inserting the text \"Hello from Claude via MCP\\n\" at line 1 column 1; then \
editor_save on \"src/hello.txt\". Then reply DONE."

# Drive the real Claude CLI. Allow only our MCP tools; capture the stream for tool-call evidence.
claude -p "$PROMPT" \
  --mcp-config "$CONFIG" \
  --allowedTools "mcp__jdesk_editor__file_create" "mcp__jdesk_editor__editor_apply_workspace_edit" "mcp__jdesk_editor__editor_save" \
  --output-format stream-json --verbose --max-turns 12 \
  > "$OUT/claude-stream.jsonl" 2>"$OUT/claude-stderr.txt"
CLAUDE_RC=$?

python3 scripts/verify_live_mcp.py "$WS" "$OUT" "$CLAUDE_RC"
