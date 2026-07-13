#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")/.."
OUT="${JDESK_EDITOR_RUN_DIR:-build/dod-out}"; mkdir -p "$OUT"
rc=0
./gradlew banned-version-markers --console=plain > "$OUT/version-lint.log" 2>&1 && VER=1 || { VER=0; rc=1; }
# Forbidden markers in product source (exclude legit System.exit / server.exit / evidence enum).
MARK=$(grep -rnE "@Disabled|@Ignore|it\.skip|describe\.skip|test\.skip|\.only\(" --include="*.java" --include="*.ts" --include="*.tsx" \
  app editor-core editor-api agent-mcp git-service terminal-service language-services evidence-core ui/src 2>/dev/null \
  | grep -v "/build/" | wc -l | tr -d ' ')
[ "$MARK" = "0" ] && DISABLED=1 || { DISABLED=0; rc=1; }
python3 - "$OUT" "$VER" "$DISABLED" "$MARK" <<'PY'
import json,sys
out,ver,dis,mark=sys.argv[1:5]
tests=[
 {"id":"DOD-version-lint","outcome":"PASS" if ver=="1" else "FAIL","detail":"no dynamic/latest versions in build files","evidence":["version-lint.log","gate-results.json"]},
 {"id":"DOD-no-disabled-tests","outcome":"PASS" if dis=="1" else "FAIL","detail":f"disabled/skipped test markers in product+ui source: {mark}","evidence":["gate-results.json"]},
]
json.dump({"tests":tests,"appInfo":{"backend":"n/a-static-audit"}},open(out+"/gate-results.json","w"),indent=2)
PY
exit $rc
