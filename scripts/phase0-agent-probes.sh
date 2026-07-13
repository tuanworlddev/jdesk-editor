#!/usr/bin/env bash
# Phase-0d live protocol de-risking: proves the real authenticated Codex and Claude CLIs
# complete their respective handshakes on this machine. Exits non-zero if either fails so the
# evidence harness records an honest PASS/FAIL. This is protocol-reachability proof, distinct
# from the Phase-4 live acceptance (which drives full agent turns through the editor).
set -uo pipefail
cd "$(dirname "$0")/.."

fail=0

echo "== Codex app-server initialize handshake =="
if node scripts/codex-handshake-probe.mjs; then
  echo "codex: PASS"
else
  echo "codex: FAIL"
  fail=1
fi

echo
echo "== Claude Code stream-json turn =="
if claude -p "Reply with exactly the two characters: OK" \
    --output-format stream-json --include-partial-messages --verbose --max-turns 1 \
    | python3 -c '
import json, sys
saw_init = saw_result = ok = False
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    try:
        m = json.loads(line)
    except Exception:
        continue
    if m.get("type") == "system" and m.get("subtype") == "init":
        saw_init = m.get("session_id") is not None
    if m.get("type") == "result":
        saw_result = True
        ok = m.get("subtype") == "success" and not m.get("is_error")
print(f"claude: init_session={saw_init} result={saw_result} success={ok}")
sys.exit(0 if (saw_init and saw_result and ok) else 1)
'; then
  echo "claude: PASS"
else
  echo "claude: FAIL"
  fail=1
fi

echo
echo "phase0-agent-probes overall: $([ $fail -eq 0 ] && echo PASS || echo FAIL)"
exit $fail
