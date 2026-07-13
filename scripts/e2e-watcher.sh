#!/usr/bin/env bash
# Watcher acceptance (spec §16, §24.3 flows 10-11, DoD "watcher mode works and is distinguished
# from semantic MCP control"): opens a file, modifies it externally on disk, and verifies the
# editor reloads it (labeled external-watcher); then creates a dirty-buffer conflict and verifies
# the buffer is NOT overwritten. Writes gate-results.json into $JDESK_EDITOR_RUN_DIR.
set -uo pipefail
cd "$(dirname "$0")/.."

OUT="${JDESK_EDITOR_RUN_DIR:-build/e2e-watcher-out}"
mkdir -p "$OUT/screenshots"
WS="$(mktemp -d)/proj"; mkdir -p "$WS"
printf 'original\n' > "$WS/watched.txt"

AUTODIR="app/build/automation"; rm -rf "$AUTODIR"
./gradlew :app:run -PjdeskPlatform=macos -PjdeskAutomation=true \
  --args="--workspace $WS" --console=plain >"$OUT/app.log" 2>&1 &
GRADLE_PID=$!
cleanup() { kill "$GRADLE_PID" 2>/dev/null; pkill -f dev.jdesk.editor.app.Main 2>/dev/null; }
trap cleanup EXIT

DESC=""
for _ in $(seq 1 90); do DESC=$(ls "$AUTODIR"/*.json 2>/dev/null | head -1 || true); [ -n "$DESC" ] && break; sleep 1; done
[ -z "$DESC" ] && { python3 scripts/write_gate_fail.py "$OUT" WATCHER "editor did not start"; exit 1; }

PORT=$(python3 -c "import json;print(json.load(open('$DESC'))['port'])")
TOKEN=$(python3 -c "import json;print(json.load(open('$DESC'))['token'])")
python3 scripts/e2e_watcher_driver.py "$PORT" "$TOKEN" "$WS" "$OUT"
RC=$?
curl -s "http://127.0.0.1:$PORT/snapshot?window=main" -H "Authorization: Bearer $TOKEN" > "$OUT/screenshots/watcher.png" || true
exit $RC
