#!/usr/bin/env bash
# CAPSTONE live test: a REAL agent (Claude) drives the RUNNING editor through MCP, and the edit
# appears live in the Monaco view. Launches the editor (automation + MCP), opens a file, runs
# Claude against the editor's own MCP config to edit+save that file, then verifies the change is
# visible in the live editor (via /evaluate) AND on disk, AND that Claude called our MCP tools.
set -uo pipefail
cd "$(dirname "$0")/.."

OUT="${JDESK_EDITOR_RUN_DIR:-build/live-agent-editor-out}"
mkdir -p "$OUT/screenshots"

command -v claude >/dev/null 2>&1 || {
  python3 scripts/write_gate_fail.py "$OUT" LIVE-AGENT-EDITOR "claude CLI not found (BLOCKED)"; exit 3; }

WS="$(mktemp -d)/proj"
mkdir -p "$WS/src"
printf 'line one\nline two\n' > "$WS/src/notes.txt"

AUTODIR="app/build/automation"
MCPDIR="app/build/mcp"
rm -rf "$AUTODIR" "$MCPDIR"

./gradlew :app:run -PjdeskPlatform=macos -PjdeskAutomation=true \
  --args="--workspace $WS" --console=plain >"$OUT/app.log" 2>&1 &
GRADLE_PID=$!
cleanup() { kill "$GRADLE_PID" 2>/dev/null; pkill -f dev.jdesk.editor.app.Main 2>/dev/null; }
trap cleanup EXIT

# Wait for both the automation endpoint and the MCP config.
DESC=""; MCPCFG="$MCPDIR/mcp-config.json"
for _ in $(seq 1 90); do
  DESC=$(ls "$AUTODIR"/*.json 2>/dev/null | head -1 || true)
  [ -n "$DESC" ] && [ -s "$MCPCFG" ] && break
  sleep 1
done
{ [ -z "$DESC" ] || [ ! -s "$MCPCFG" ]; } && {
  python3 scripts/write_gate_fail.py "$OUT" LIVE-AGENT-EDITOR "editor/MCP did not come up"; exit 1; }

PORT=$(python3 -c "import json;print(json.load(open('$DESC'))['port'])")
TOKEN=$(python3 -c "import json;print(json.load(open('$DESC'))['token'])")

ev() { curl -s -X POST "http://127.0.0.1:$PORT/evaluate" -H "Authorization: Bearer $TOKEN" \
       -H "Content-Type: application/json" -d "{\"window\":\"main\",\"script\":$1}"; }

# Open the file in the running editor first (so the agent's edit must appear live).
ev '"window.__editorDriver.openFile(\"src/notes.txt\")"' >/dev/null
sleep 1

PROMPT="You are connected to the JDesk Editor via the jdesk_editor MCP server. Using ONLY those \
tools: call editor_apply_workspace_edit on relPath \"src/notes.txt\" to insert the text \
\"AGENT WAS HERE\\n\" at line 1 column 1; then call editor_save on \"src/notes.txt\". Then reply DONE."

claude -p "$PROMPT" --mcp-config "$MCPCFG" \
  --allowedTools "mcp__jdesk_editor__editor_apply_workspace_edit" "mcp__jdesk_editor__editor_save" "mcp__jdesk_editor__editor_open" \
  --output-format stream-json --verbose --max-turns 12 \
  > "$OUT/claude-stream.jsonl" 2>"$OUT/claude-stderr.txt"
CLAUDE_RC=$?
sleep 1

# Read the LIVE editor's Monaco content after the agent's edit.
LIVE_TEXT=$(ev '"window.__editorProbe.activeText()"' | python3 -c "import json,sys;print(json.loads(sys.stdin.read()).get('result',''))")
echo "$LIVE_TEXT" > "$OUT/live-editor-text.txt"
curl -s "http://127.0.0.1:$PORT/snapshot?window=main" -H "Authorization: Bearer $TOKEN" > "$OUT/screenshots/live-editor.png" || true
curl -s "http://127.0.0.1:$PORT/console?window=main" -H "Authorization: Bearer $TOKEN" > "$OUT/console.json" || true

python3 scripts/verify_live_agent_editor.py "$WS" "$OUT" "$CLAUDE_RC"
