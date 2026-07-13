#!/usr/bin/env bash
# Phase-1 native E2E: drives the REAL editor (open workspace → open file → edit through Monaco →
# save) via the automation endpoint and verifies the exact bytes on disk. Writes gate-results.json
# + app-info.json + console.json + screenshots into $JDESK_EDITOR_RUN_DIR for evidence ingestion.
# Exits 0 iff all checks pass.
set -uo pipefail
cd "$(dirname "$0")/.."

OUT="${JDESK_EDITOR_RUN_DIR:-build/e2e-out}"
mkdir -p "$OUT/screenshots"
WS="$(mktemp -d)/demo"
mkdir -p "$WS/src"
printf '# Demo\n' > "$WS/README.md"
printf 'class App {}\n' > "$WS/src/App.java"

AUTODIR="app/build/automation"
rm -rf "$AUTODIR"

# Launch the editor (gradle run serves ui/dist over jdesk://app and enables the automation
# endpoint). The app runs until we close it.
./gradlew :app:run -PjdeskPlatform=macos -PjdeskAutomation=true \
  --args="--workspace $WS" --console=plain >"$OUT/app.log" 2>&1 &
GRADLE_PID=$!
cleanup() { kill "$GRADLE_PID" 2>/dev/null; pkill -f dev.jdesk.editor.app.Main 2>/dev/null; }
trap cleanup EXIT

DESC=""
for _ in $(seq 1 90); do
  DESC=$(ls "$AUTODIR"/*.json 2>/dev/null | head -1 || true)
  [ -n "$DESC" ] && break
  sleep 1
done
[ -z "$DESC" ] && { echo "no automation descriptor"; python3 scripts/write_gate_fail.py "$OUT" E2E-EDITOR "editor did not start / no automation descriptor"; exit 1; }

PORT=$(python3 -c "import json;print(json.load(open('$DESC'))['port'])")
TOKEN=$(python3 -c "import json;print(json.load(open('$DESC'))['token'])")

python3 scripts/e2e_editor_driver.py "$PORT" "$TOKEN" "$WS" "$OUT"
RC=$?

curl -s "http://127.0.0.1:$PORT/console?window=main" -H "Authorization: Bearer $TOKEN" > "$OUT/console.json" || true
curl -s "http://127.0.0.1:$PORT/snapshot?window=main" -H "Authorization: Bearer $TOKEN" > "$OUT/screenshots/editor.png" || true

exit $RC
