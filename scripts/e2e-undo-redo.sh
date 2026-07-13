#!/usr/bin/env bash
# Undo/redo native E2E (spec §24.3 flow 9). Types an edit, undoes to original, redoes to edited.
set -uo pipefail
cd "$(dirname "$0")/.."
OUT="${JDESK_EDITOR_RUN_DIR:-build/e2e-undo-out}"; mkdir -p "$OUT/screenshots"
WS="$(mktemp -d)/proj"; mkdir -p "$WS"; printf 'base\n' > "$WS/f.txt"
AUTODIR="app/build/automation"; rm -rf "$AUTODIR"
./gradlew :app:run -PjdeskPlatform=macos -PjdeskAutomation=true --args="--workspace $WS" --console=plain >"$OUT/app.log" 2>&1 &
GP=$!; trap 'kill $GP 2>/dev/null; pkill -f dev.jdesk.editor.app.Main 2>/dev/null' EXIT
DESC=""; for _ in $(seq 1 90); do DESC=$(ls "$AUTODIR"/*.json 2>/dev/null|head -1||true); [ -n "$DESC" ] && break; sleep 1; done
[ -z "$DESC" ] && { python3 scripts/write_gate_fail.py "$OUT" UNDO "no editor"; exit 1; }
PORT=$(python3 -c "import json;print(json.load(open('$DESC'))['port'])"); TOKEN=$(python3 -c "import json;print(json.load(open('$DESC'))['token'])")
python3 scripts/e2e_undo_driver.py "$PORT" "$TOKEN" "$OUT"
RC=$?
curl -s "http://127.0.0.1:$PORT/snapshot?window=main" -H "Authorization: Bearer $TOKEN" > "$OUT/screenshots/undo.png" || true
exit $RC
