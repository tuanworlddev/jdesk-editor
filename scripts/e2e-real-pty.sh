#!/usr/bin/env bash
# Real PTY acceptance (spec §17, DoD): opens a real PTY in the RUNNING editor via the MCP
# terminal tools, runs a command, and verifies the output marker and exit code. Writes
# gate-results.json into $JDESK_EDITOR_RUN_DIR. Exits 0 iff the PTY produced the marker and the
# expected exit code.
set -uo pipefail
cd "$(dirname "$0")/.."

OUT="${JDESK_EDITOR_RUN_DIR:-build/e2e-pty-out}"
mkdir -p "$OUT/screenshots"
WS="$(mktemp -d)/proj"; mkdir -p "$WS"
printf 'placeholder\n' > "$WS/README.md"

AUTODIR="app/build/automation"; MCPDIR="app/build/mcp"
rm -rf "$AUTODIR" "$MCPDIR"

./gradlew :app:run -PjdeskPlatform=macos -PjdeskAutomation=true \
  --args="--workspace $WS" --console=plain >"$OUT/app.log" 2>&1 &
GRADLE_PID=$!
cleanup() { kill "$GRADLE_PID" 2>/dev/null; pkill -f dev.jdesk.editor.app.Main 2>/dev/null; }
trap cleanup EXIT

DESC=""; MCPCFG="$MCPDIR/mcp-config.json"
for _ in $(seq 1 90); do
  DESC=$(ls "$AUTODIR"/*.json 2>/dev/null | head -1 || true)
  [ -n "$DESC" ] && [ -s "$MCPCFG" ] && break
  sleep 1
done
{ [ -z "$DESC" ] || [ ! -s "$MCPCFG" ]; } && {
  python3 scripts/write_gate_fail.py "$OUT" PTY-E2E "editor/MCP did not come up"; exit 1; }

MCP_URL=$(python3 -c "import json;print(json.load(open('$MCPCFG'))['mcpServers']['jdesk_editor']['url'])")
MCP_AUTH=$(python3 -c "import json;print(json.load(open('$MCPCFG'))['mcpServers']['jdesk_editor']['headers']['Authorization'])")

python3 scripts/e2e_pty_driver.py "$MCP_URL" "$MCP_AUTH" "$OUT"
RC=$?

DESC_PORT=$(python3 -c "import json;print(json.load(open('$DESC'))['port'])")
DESC_TOKEN=$(python3 -c "import json;print(json.load(open('$DESC'))['token'])")
curl -s "http://127.0.0.1:$DESC_PORT/snapshot?window=main" -H "Authorization: Bearer $DESC_TOKEN" > "$OUT/screenshots/pty.png" || true
exit $RC
