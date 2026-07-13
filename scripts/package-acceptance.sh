#!/usr/bin/env bash
# Packaging acceptance (spec §26, DoD "production packages were built and launched"): builds the
# jpackage app-image + DMG, launches the app-image with --jdesk-smoke (real launch, exit 0),
# verifies the DMG artifact launches, and checks the production package excludes the test-only
# automation module. Writes gate-results.json + app-info.json into $JDESK_EDITOR_RUN_DIR.
set -uo pipefail
cd "$(dirname "$0")/.."

OUT="${JDESK_EDITOR_RUN_DIR:-build/package-out}"
mkdir -p "$OUT"
PKG_APP="app/build/jdesk/package/editor.app"
LAUNCHER="$PKG_APP/Contents/MacOS/editor"
DMG="app/build/jdesk/installer/editor-1.0.0.dmg"

pass_fail() { # id ok detail
  local ok="$2"; local outcome="FAIL"; [ "$ok" = "1" ] && outcome="PASS"
  python3 - "$OUT" "$1" "$outcome" "$3" <<'PY'
import json,os,sys
out,tid,outcome,detail=sys.argv[1:5]
f=os.path.join(out,"gate-results.json")
data=json.load(open(f)) if os.path.exists(f) else {"tests":[]}
data["tests"].append({"id":tid,"outcome":outcome,"detail":detail,
                      "evidence":["gate-results.json","commands.jsonl"]})
json.dump(data,open(f,"w"),indent=2)
PY
  echo "$outcome $1 — $3"
}

# Build fresh.
./gradlew :app:jdeskPackage :app:jdeskInstaller -PjdeskPlatform=macos -PjdeskInstallerType=dmg \
  --console=plain > "$OUT/package-build.log" 2>&1
BUILD_RC=$?
pass_fail PKG-01-build "$([ $BUILD_RC -eq 0 ] && [ -x "$LAUNCHER" ] && echo 1 || echo 0)" \
  "jdeskPackage+jdeskInstaller exit=$BUILD_RC, launcher present=$([ -x "$LAUNCHER" ] && echo yes || echo no)"

# Launch the app-image with --jdesk-smoke; require exit 0 and the smoke marker.
SMOKE_OK=0
if [ -x "$LAUNCHER" ]; then
  "$LAUNCHER" --jdesk-smoke > "$OUT/appimage-smoke.log" 2>&1 &
  PID=$!
  for _ in $(seq 1 60); do kill -0 $PID 2>/dev/null || break; sleep 1; done
  wait $PID 2>/dev/null; RC=$?
  grep -q "JDESK-EDITOR-SMOKE OK" "$OUT/appimage-smoke.log" && [ $RC -eq 0 ] && SMOKE_OK=1
fi
pass_fail PKG-02-appimage-launch "$SMOKE_OK" "packaged app-image launched, self-checked, exit 0"

# Production purity: no test-only automation module in the package.
AUTO_PRESENT=$(ls "$PKG_APP/Contents/app/" 2>/dev/null | grep -c "jdesk-automation" || true)
pass_fail PKG-03-no-automation "$([ "$AUTO_PRESENT" = "0" ] && echo 1 || echo 0)" \
  "production package excludes jdesk-automation (found=$AUTO_PRESENT)"

# DMG acceptance: mount, copy the .app out, detach, launch its smoke.
DMG_OK=0
if [ -f "$DMG" ]; then
  MNT=$(mktemp -d)
  if hdiutil attach "$DMG" -nobrowse -readonly -mountpoint "$MNT" >/dev/null 2>&1; then
    DMG_APP=$(find "$MNT" -maxdepth 1 -name "*.app" | head -1)
    if [ -n "$DMG_APP" ]; then
      COPY=$(mktemp -d)/editor.app
      cp -R "$DMG_APP" "$COPY"
      hdiutil detach "$MNT" >/dev/null 2>&1 || true
      "$COPY/Contents/MacOS/editor" --jdesk-smoke > "$OUT/dmg-smoke.log" 2>&1 &
      PID=$!
      for _ in $(seq 1 60); do kill -0 $PID 2>/dev/null || break; sleep 1; done
      wait $PID 2>/dev/null; RC=$?
      grep -q "JDESK-EDITOR-SMOKE OK" "$OUT/dmg-smoke.log" && [ $RC -eq 0 ] && DMG_OK=1
    else
      hdiutil detach "$MNT" >/dev/null 2>&1 || true
    fi
  fi
  shasum -a 256 "$DMG" > "$OUT/dmg.sha256"
fi
pass_fail PKG-04-dmg-launch "$DMG_OK" "DMG mounted, .app copied out and launched with exit 0"

python3 - "$OUT" <<'PY'
import json,os,sys
out=sys.argv[1]
json.dump({"backend":"macos-wkwebview"},open(os.path.join(out,"app-info.json"),"w"),indent=2)
data=json.load(open(os.path.join(out,"gate-results.json")))
data["appInfo"]={"backend":"macos-wkwebview"}
json.dump(data,open(os.path.join(out,"gate-results.json"),"w"),indent=2)
sys.exit(0 if all(t["outcome"]=="PASS" for t in data["tests"]) else 1)
PY
