# Definition of Done — Audit

Maps every spec §29 checkbox to its status and evidence. Evidence run IDs resolve to
`docs/verification/runs/<runId>/` (tamper-evident stubs; full runs gitignored). Statuses are
honest: PARTIAL and BLOCKED are never reported as done.

| # | DoD item | Status | Evidence |
|---|---|---|---|
| 1 | Actual JDesk source/version audited and recorded | ✅ DONE | `docs/JDESK_AUDIT.md`; runs `…102748Z-dc23`, `…103029Z-6ff9`, `…103116Z-b8b4` |
| 2 | Monaco worker gate passed on native WebViews (or BLOCKED) | ✅ DONE (macOS); Win/Linux BLOCKED | `…105334Z-d829` (10/10 on WKWebView); `docs/MONACO_WORKER_STRATEGY.md` |
| 3 | Normal editor workflows function without an agent | ✅ DONE | `…111904Z-44ef` (E2E open/edit/save, byte-exact) |
| 4 | Semantic actions use stable IDs, never agent coordinates | ✅ DONE | Explorer/tab semantic ids (E2E-01); pointer geometry in-page only (`…105336Z-e06d` S2) |
| 5 | Pointer overlay activates real application commands | ⚠️ PARTIAL | Phase-0 pointer proof (`…105336Z-e06d` S2); full choreography queue not built |
| 6 | All presentation modes produce byte-identical final content | ✅ DONE | `…121445Z-a099` (INSTANT/LIVE/CINEMATIC identical hash) |
| 7 | Version conflicts and edit leases prevent silent overwrites | ✅ DONE | `DocumentStoreTest` (version conflict, lease interrupt); `…121838Z-995a` |
| 8 | Watcher mode works, distinguished from semantic MCP control | ✅ DONE | `…121128Z-fe91` (external reload labeled external-watcher; dirty conflict no overwrite) |
| 9 | MCP is authenticated, capability-scoped, and path-safe | ✅ DONE | `McpServerIT` (401, traversal→boundary); `docs/SECURITY.md`; `…121838Z-995a` |
| 10 | Embedded Codex passed a live authenticated workflow | ⚠️ PARTIAL | Codex app-server handshake live (`…103004Z-5b9b`); embedded app-managed Codex turn not built (Claude embedded is done, #11) |
| 11 | Embedded Claude passed a live authenticated workflow | ✅ DONE | `…122144Z-df79` (editor spawns+manages Claude; edit appears live + on disk) |
| 12 | Real PTY, Git, and real LSP acceptance passed | ✅ DONE | PTY `…120153Z-e664`; Git `…115210Z-01da`; LSP `…115521Z-4317` (real TS + pyright) |
| 13 | Required native E2E flows passed on available platforms | ⚠️ PARTIAL | Flows 1-8,10-13 done across the E2E runs; undo/redo, LSP-navigate, git-diff-view, close/restore not yet |
| 14 | Production packages were built and launched | ✅ DONE | `…120727Z-337d` (app-image + DMG built, launched, exit 0; automation excluded) |
| 15 | Acceptance runs contain no unhandled WebView console errors | ✅ DONE | Gate GATE-10 console-clean; app runs console-forwarded |
| 16 | Unit/integration/E2E/live/security/perf results recorded honestly | ✅ DONE | `VERIFICATION.md` (55 rows); this audit |
| 17 | Every PASS in VERIFICATION.md points to real evidence | ✅ DONE | `evidence-cli report` refuses unbacked PASS; `evidence-cli verify` clean |
| 18 | No critical TODO, disabled test, placeholder, fake, fabricated success | ✅ DONE | Forbidden-marker scan clean; banned-version lint clean |
| 19 | A clean checkout builds with documented commands | ✅ DONE | Cloned checkout built frontend + 68 unit tests green with README commands |

## Cross-platform ledger (spec §24.6)

| Platform | Status | Remediation |
|---|---|---|
| macOS arm64 / WKWebView | ✅ All acceptance runs executed here | — |
| Windows / WebView2 | ⛔ BLOCKED — no hardware/VM | Clone on Windows 11 x64 + JDK 25 + Node; run `gradlew :app:run/:app:jdeskPackage -PjdeskPlatform=windows` and the E2E scripts (Windows-safe) |
| Linux / WebKitGTK | ⛔ BLOCKED — Docker daemon not running this session | Start Docker Desktop, build the `ubuntu:24.04 + libwebkit2gtk-4.1 + Xvfb` image, run the gate under `xvfb-run`; classify as container/Xvfb, not bare metal |

## Honesty statement

The agent-native core is complete and proven with live evidence: a real Claude session — both as an
external MCP client and as an editor-spawned embedded session — drives the running editor, and the
edits appear live in Monaco and on disk. Remaining items (embedded Codex turn, full pointer
choreography, the last few native E2E flows, and Windows/Linux runs) are documented above as
PARTIAL or BLOCKED and are not reinterpreted as done.
