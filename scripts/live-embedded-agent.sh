#!/usr/bin/env bash
# LIVE embedded-agent acceptance (spec §15, DoD "Embedded Claude passed a live authenticated
# workflow"): the RUNNING editor itself spawns and manages a real Claude session (agent.startClaude)
# pointed at the editor's own MCP server. Verifies the embedded agent edited a file that appears
# live in the editor and on disk, and that it called our MCP tools. BLOCKED (exit 3) if claude
# is unavailable.
set -uo pipefail
cd "$(dirname "$0")/.."

OUT="${JDESK_EDITOR_RUN_DIR:-build/live-embedded-out}"
mkdir -p "$OUT/screenshots"
command -v claude >/dev/null 2>&1 || {
  python3 scripts/write_gate_fail.py "$OUT" LIVE-EMBEDDED "claude CLI not found (BLOCKED)"; exit 3; }

WS="$(mktemp -d)/proj"; mkdir -p "$WS/src"
printf 'seed\n' > "$WS/src/target.txt"

AUTODIR="app/build/automation"; MCPDIR="app/build/mcp"; rm -rf "$AUTODIR" "$MCPDIR"
./gradlew :app:run -PjdeskPlatform=macos -PjdeskAutomation=true \
  --args="--workspace $WS" --console=plain >"$OUT/app.log" 2>&1 &
GRADLE_PID=$!
cleanup() { kill "$GRADLE_PID" 2>/dev/null; pkill -f dev.jdesk.editor.app.Main 2>/dev/null; pkill -f "claude" 2>/dev/null; }
trap cleanup EXIT

DESC=""; MCPCFG="$MCPDIR/mcp-config.json"
for _ in $(seq 1 90); do
  DESC=$(ls "$AUTODIR"/*.json 2>/dev/null | head -1 || true)
  [ -n "$DESC" ] && [ -s "$MCPCFG" ] && break
  sleep 1
done
{ [ -z "$DESC" ] || [ ! -s "$MCPCFG" ]; } && {
  python3 scripts/write_gate_fail.py "$OUT" LIVE-EMBEDDED "editor/MCP did not come up"; exit 1; }

PORT=$(python3 -c "import json;print(json.load(open('$DESC'))['port'])")
TOKEN=$(python3 -c "import json;print(json.load(open('$DESC'))['token'])")
python3 scripts/live_embedded_driver.py "$PORT" "$TOKEN" "$WS" "$OUT"
RC=$?
curl -s "http://127.0.0.1:$PORT/snapshot?window=main" -H "Authorization: Bearer $TOKEN" > "$OUT/screenshots/embedded.png" || true
exit $RC
