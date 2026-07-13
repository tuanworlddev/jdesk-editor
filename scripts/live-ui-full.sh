#!/usr/bin/env bash
# LIVE UI acceptance: drives the redesigned running editor with a REAL embedded Claude agent and a
# REAL terminal, verifying the new UI features end-to-end (menubar wiring, panels, MDI, streaming
# agent edits with the following pointer, terminal PTY). Writes gate-results.json + screenshots.
# BLOCKED (exit 3) if claude is unavailable.
set -uo pipefail
cd "$(dirname "$0")/.."

OUT="${JDESK_EDITOR_RUN_DIR:-build/live-ui-out}"
mkdir -p "$OUT/screenshots"
command -v claude >/dev/null 2>&1 || {
  python3 scripts/write_gate_fail.py "$OUT" LIVE-UI "claude CLI not found (BLOCKED)"; exit 3; }

WS="$(mktemp -d)/proj"; mkdir -p "$WS/src"
printf 'seed line\n' > "$WS/src/story.txt"
printf '# Live UI test workspace\n' > "$WS/README.md"

AUTODIR="app/build/automation"; MCPDIR="app/build/mcp"; rm -rf "$AUTODIR" "$MCPDIR"
./gradlew :app:run -PjdeskPlatform=macos -PjdeskAutomation=true \
  --args="--workspace $WS" --console=plain >"$OUT/app.log" 2>&1 &
GRADLE_PID=$!
cleanup() { kill "$GRADLE_PID" 2>/dev/null; pkill -f dev.jdesk.editor.app.Main 2>/dev/null; pkill -f claude 2>/dev/null; }
trap cleanup EXIT

DESC=""; MCPCFG="$MCPDIR/mcp-config.json"
for _ in $(seq 1 90); do
  DESC=$(ls "$AUTODIR"/*.json 2>/dev/null | head -1 || true)
  [ -n "$DESC" ] && [ -s "$MCPCFG" ] && break
  sleep 1
done
{ [ -z "$DESC" ] || [ ! -s "$MCPCFG" ]; } && {
  python3 scripts/write_gate_fail.py "$OUT" LIVE-UI "editor/MCP did not come up"; exit 1; }

PORT=$(python3 -c "import json;print(json.load(open('$DESC'))['port'])")
TOKEN=$(python3 -c "import json;print(json.load(open('$DESC'))['token'])")
python3 scripts/live_ui_driver.py "$PORT" "$TOKEN" "$WS" "$OUT"
