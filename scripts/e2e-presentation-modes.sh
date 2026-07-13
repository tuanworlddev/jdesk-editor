#!/usr/bin/env bash
# Presentation-mode acceptance (spec §12.4, DoD "all presentation modes produce byte-identical
# final content"): applies the same staged edit in INSTANT, LIVE, and CINEMATIC modes in the
# running editor and asserts all three final content hashes are identical. Writes gate-results.json.
set -uo pipefail
cd "$(dirname "$0")/.."

OUT="${JDESK_EDITOR_RUN_DIR:-build/e2e-modes-out}"
mkdir -p "$OUT/screenshots"
WS="$(mktemp -d)/proj"; mkdir -p "$WS"; printf 'seed\n' > "$WS/f.txt"

AUTODIR="app/build/automation"; rm -rf "$AUTODIR"
./gradlew :app:run -PjdeskPlatform=macos -PjdeskAutomation=true \
  --args="--workspace $WS" --console=plain >"$OUT/app.log" 2>&1 &
GRADLE_PID=$!
cleanup() { kill "$GRADLE_PID" 2>/dev/null; pkill -f dev.jdesk.editor.app.Main 2>/dev/null; }
trap cleanup EXIT

DESC=""
for _ in $(seq 1 90); do DESC=$(ls "$AUTODIR"/*.json 2>/dev/null | head -1 || true); [ -n "$DESC" ] && break; sleep 1; done
[ -z "$DESC" ] && { python3 scripts/write_gate_fail.py "$OUT" MODES "editor did not start"; exit 1; }
PORT=$(python3 -c "import json;print(json.load(open('$DESC'))['port'])")
TOKEN=$(python3 -c "import json;print(json.load(open('$DESC'))['token'])")
python3 scripts/e2e_modes_driver.py "$PORT" "$TOKEN" "$OUT"
RC=$?
curl -s "http://127.0.0.1:$PORT/snapshot?window=main" -H "Authorization: Bearer $TOKEN" > "$OUT/screenshots/modes.png" || true
exit $RC
