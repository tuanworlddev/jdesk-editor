# JDesk Editor — Implementation Status

Honest accounting of what is built and proven versus what remains (spec §29: blocked or unexecuted
checks are never reported as success). The authoritative per-checkbox breakdown is
**[docs/verification/DOD_AUDIT.md](verification/DOD_AUDIT.md)**; `VERIFICATION.md` lists all 55
evidence-backed acceptance rows.

**Host reality:** macOS 26.5.1 arm64 is the only physical platform. Windows and Linux are BLOCKED
(no Windows hardware; Docker daemon unavailable this session). Cross-platform completion is not
claimed.

## Done and proven with live evidence

- **Phase 0 (hard gate)** — JDesk audited; Monaco worker gate 10/10 on WKWebView; semantic/pointer/
  hash proofs; live Codex + Claude protocol handshakes.
- **Phase 1 (running editor)** — open/edit/save without an agent (byte-exact); document model
  (path safety, version conflicts, leases, atomic save, 38 tests); first consumer of JDesk codegen.
- **Phase 3 (MCP + live agent)** — authenticated MCP server (12 tools); a real Claude session drives
  the editor (external) and the editor spawns+manages Claude (embedded); edits appear live in Monaco
  and on disk.
- **Phase 5 (developer tooling)** — real Git, real LSP (typescript-language-server + pyright), real
  PTY through the running editor, terminal 10 MiB backpressure, workspace search.
- **Phase 6 (hardening/release)** — macOS app-image + DMG built and launched (automation excluded);
  filesystem watcher (external reload / dirty conflict, distinctly labeled); three byte-identical
  presentation modes; performance recorded (command-ack p95 = 1 ms); security documented; clean-
  checkout build verified; forbidden-marker + version lints clean.

19 of 19 DoD items are DONE or explicitly PARTIAL/BLOCKED — see DOD_AUDIT.md. 15 DONE, 3 PARTIAL,
plus the cross-platform BLOCKED ledger.

## Remaining (honestly incomplete — not claimed as passing)

- **Embedded Codex live turn** — the protocol handshake is proven; the app-managed Codex session
  (spawn `codex app-server`, JSONL turn lifecycle, approval bridging) is not built. (Embedded Claude
  is done; external MCP control by any agent is done.)
- **Full pointer choreography** — the pointer geometry proof and the three presentation modes exist;
  the ACCEPTED→COMMITTED→PRESENTED choreography queue and a production semantic registry are not
  fully built.
- **Remaining MCP tools** — `ui.*`, `file.rename/delete` (+ approval gate/UI), `agent.wait_for_state`
  (12 of 18 implemented).
- **Last native E2E flows** — undo/redo, LSP-navigate-to-diagnostic, git-diff-view, close/restore.
- **Cross-platform** — Windows (WebView2) and Linux (WebKitGTK) runs are BLOCKED with documented
  remediation.

## Reproduce

```
./gradlew :editor-core:test :agent-mcp:test :evidence-core:test :app:test :terminal-service:test :git-service:test
./gradlew integrationTest                                   # real Git + LSP
evidence-core/build/install/evidence-core/bin/evidence-core verify
# Running editor + live embedded agent (real Claude quota):
evidence-core/build/install/evidence-core/bin/evidence-core app-run live-agent live-embedded-claude -- bash scripts/live-embedded-agent.sh
```
