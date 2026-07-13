# JDesk Editor — Implementation Status

Honest accounting of what is built and proven versus what remains, per spec §29 (blocked or
unexecuted checks are never reported as success). Every PASS below links to an evidence run under
`docs/verification/runs/<runId>/` (tamper-evident stubs; full runs are gitignored under
`artifacts/test-runs/`). Verify any run with `evidence-cli verify`.

**Host reality:** macOS 26.5.1 arm64 is the only physical platform. Windows is BLOCKED (no
hardware). Linux via Docker is not yet attempted. Cross-platform completion is not claimed.

## Proven with evidence

### Phase 0 — Audit + proofs (HARD GATE PASSED)
- **JDesk framework audited** on this machine (`docs/JDESK_AUDIT.md`): its own `check` (macOS-scoped)
  PASS, native-smoke PASS (real WKWebView WebKit 21624, full IPC/2 GiB-stream/PTY smoke),
  security-probe PASS. Full `clean check` fails only on `WindowsMenuTest` loading Windows DLLs on
  macOS — a documented known-issue in a module the editor never loads.
- **Monaco worker gate**: 10/10 spec §6.1 items PASS on WKWebView at the production `jdesk://app`
  origin (base/TS/JSON workers, completion, diagnostics, correct `text/javascript` MIME, CSP with
  `eval` blocked, clean console). Strategy pinned: same-origin ESM workers
  (`docs/MONACO_WORKER_STRATEGY.md`). This retired the project's single biggest risk.
- **Semantic micro-proof**: registry ID stability, in-page pointer geometry (coordinates never
  cross the bridge), a typed Monaco edit transaction, single undo group, and Java `MessageDigest`
  SHA-256 == frontend `@noble/hashes` SHA-256 on identical bytes (S3-JAVA).
- **Live agent protocol probes**: real `codex app-server` initialize handshake and `claude`
  stream-json turn both succeed.

### Phase 1 — Editor foundation (running editor, no agent)
- **Running editor verified end-to-end** against the live app (E2E-01..06): open a workspace →
  Explorer with stable semantic ids → open a file → edit through Monaco (delta reaches Java,
  version advances) → dirty tracking → **save writes byte-exact content to disk (SHA-256 match)** →
  dirty clears. This satisfies **Definition-of-Done item 3** (normal editor workflows without an agent).
- **Document model** (33 tests): `PathService` (traversal/symlink-escape/containment choke point),
  `DocumentStore` (version/hash/lease state machine, optimistic concurrency, human-priority lease
  interruption, external-change reload vs dirty-conflict, self-save suppression), `TextEdits`
  (Monaco-faithful, order-independent hash), `AtomicSaver` (temp+fsync+ATOMIC_MOVE, permission
  preservation, disk-hash re-check, CRLF fidelity), `FileTree` (lazy Explorer, ignore dirs).
- **First working consumer of JDesk's typed codegen** (`ui/src/generated/`), consumed through the
  vendored `jdesk-client`.

### Phase 3 — MCP server + LIVE agent control (the defining capability)
- **A real authenticated agent (Claude Code) drives the editor through MCP** (LIVE-CLAUDE-01..03):
  connected over authenticated loopback HTTP, called `file_create` → `editor_apply_workspace_edit`
  → `editor_save`, and the exact bytes landed on disk.
- **Capstone — agent edits the RUNNING editor live** (LIVE-AGENT-01..03): Claude edited a file open
  in the running editor via MCP, and the change appeared **live in the Monaco view** and on disk.
  This is the full agent-native thesis: semantic tool calls driving the real running application,
  no screenshots, no coordinate guessing.
- **MCP server** (`agent-mcp`, 7 integration tests over real loopback HTTP): hand-rolled Streamable
  HTTP JSON-RPC 2.0 (rev 2025-11-25), 256-bit per-run bearer token, owner-only discovery file,
  Origin check, protocol-version negotiation, 8 tools with underscore names + dotted aliases,
  §13.2 mutation envelopes, §13.3 error codes, traversal rejection.

### Evidence discipline
- `evidence-core` (14 self-tests): refuses a PASS without run-produced evidence, refuses to start
  against a drifted/dirty JDesk checkout (SHA-pinned provenance), catches tampered checksums,
  requires a real WebView backend for native categories, redacts secrets. Every acceptance run
  above was produced and verified by this harness.

## Not yet done / remaining

These are honestly incomplete — not claimed as passing.

- **Phase 1 remainder**: filesystem watcher wiring to the UI, crash-recovery journal, settings /
  layout / recents persistence, undo/redo E2E flow, close-confirm dialog, workspace search, the
  full 17 native E2E flows (spec §24.3), and the 50k-file interactivity performance run (§22).
- **Phase 2 (semantic interaction) — full scope**: the production semantic UI registry, the Action
  Coordinator as the sole agent-intent mutation path, the pointer actor with choreography
  (ACCEPTED→COMMITTED→PRESENTED), and the three presentation modes (CINEMATIC/LIVE/INSTANT) with
  byte-identical output. A minimal semantic/pointer/edit proof exists (Phase 0), and agent edits
  already appear live, but the full §9–§12 machinery is not built.
- **Phase 3 remainder**: the remaining MCP tools (`ui.snapshot/activate/focus`, `terminal.*`,
  `file.rename/delete`, `agent.wait_for_state`) to reach the full 18, the approval gate + native
  approval UI for destructive operations, and the Codex MCP-client test.
- **Phase 4 (embedded agent adapters)**: the app-managed embedded Codex (`codex app-server`) and
  Claude (`claude` stream-json) sessions per spec §14–§15, the agent conversation/timeline/approval
  UI, and the full live acceptance (fix-a-failing-test task, approvals, interrupt+resume). Note:
  live *external* MCP control by a real agent is already proven above; the *embedded* app-managed
  session lifecycle is not built.
- **Phase 5**: terminal (framework PTY + xterm.js + 10 MiB stress), workspace search UI, Git
  status/diff, and real LSP (typescript-language-server + pyright).
- **Phase 6**: jpackage `.app`/DMG packaging + packaged smoke, performance doc (§22), crash-recovery
  drills, production/e2e flavor separation + purity test, and the final DoD audit.
- **Cross-platform**: Windows (WebView2) is **BLOCKED** — no hardware; remediation is to run the
  documented lanes on a Windows 11 x64 host. Linux (WebKitGTK via Docker) is **not yet attempted**.

## How to reproduce

```
# Unit/integration tests (headless)
./gradlew :editor-core:test :agent-mcp:test :evidence-core:test :app:test

# Monaco worker gate (opens a real window)
./gradlew :evidence-core:installDist :e2e:gate-app:installDist -PjdeskPlatform=macos
evidence-core/build/install/evidence-core/bin/evidence-core app-run gate monaco-worker-gate -- \
  e2e/gate-app/build/install/gate-app/bin/gate-app --gate monaco

# Running editor + live agent capstone (uses real Claude quota)
evidence-core/build/install/evidence-core/bin/evidence-core app-run e2e-native live-agent-editor -- \
  bash scripts/live-agent-editor.sh

# Audit all evidence
evidence-core/build/install/evidence-core/bin/evidence-core verify
```
