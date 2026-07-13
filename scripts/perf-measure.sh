#!/usr/bin/env bash
# Performance measurement (spec §22 → docs/PERFORMANCE.md). Measures command-ack and warm
# file-open latency against the running editor and writes results + a report. Writes
# gate-results.json into $JDESK_EDITOR_RUN_DIR.
set -uo pipefail
cd "$(dirname "$0")/.."

OUT="${JDESK_EDITOR_RUN_DIR:-build/perf-out}"
mkdir -p "$OUT"
WS="$(mktemp -d)/proj"; mkdir -p "$WS/src"
python3 -c "print('x'*2000)" > "$WS/src/medium.txt"

AUTODIR="app/build/automation"; rm -rf "$AUTODIR"
./gradlew :app:run -PjdeskPlatform=macos -PjdeskAutomation=true \
  --args="--workspace $WS" --console=plain >"$OUT/app.log" 2>&1 &
GRADLE_PID=$!
cleanup() { kill "$GRADLE_PID" 2>/dev/null; pkill -f dev.jdesk.editor.app.Main 2>/dev/null; }
trap cleanup EXIT

DESC=""
for _ in $(seq 1 90); do DESC=$(ls "$AUTODIR"/*.json 2>/dev/null | head -1 || true); [ -n "$DESC" ] && break; sleep 1; done
[ -z "$DESC" ] && { python3 scripts/write_gate_fail.py "$OUT" PERF "editor did not start"; exit 1; }
PORT=$(python3 -c "import json;print(json.load(open('$DESC'))['port'])")
TOKEN=$(python3 -c "import json;print(json.load(open('$DESC'))['token'])")
python3 scripts/perf_driver.py "$PORT" "$TOKEN" "$OUT"
