# Changelog

## 0.1.0 (unreleased)

Agent-native desktop code editor on JDesk. First working slice, verified with live evidence.

- Phase 0: JDesk audit; Monaco worker gate on WKWebView (10/10); semantic/pointer/hash proofs;
  live Codex + Claude protocol handshakes. Anti-fabrication evidence harness.
- Phase 1: running editor — open/edit/save without an agent (byte-exact, verified). Document model
  with path safety, version conflicts, leases, atomic save. First consumer of JDesk typed codegen.
- Phase 3: authenticated MCP server; a real Claude session drives the editor; edits appear live in
  Monaco (external + embedded, editor-managed).
- Phase 5: real Git, real LSP (TS + pyright), real PTY through the editor, terminal backpressure,
  workspace search.
- Phase 6: macOS app-image + DMG built and launched; filesystem watcher; three byte-identical
  presentation modes; performance recorded; security documented.

See docs/STATUS.md and docs/verification/DOD_AUDIT.md for the honest per-item breakdown, including
remaining and BLOCKED items (embedded Codex turn, full pointer choreography, Windows/Linux runs).
