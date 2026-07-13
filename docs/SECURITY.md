# Security

The editor treats the WebView as untrusted relative to the Java core (spec §21). This document
records the trust boundaries, path handling, tokens, approvals, and threat model, and links each
control to the test that exercises it.

## Trust boundaries

- **WebView ↔ Java**: the frontend is untrusted. Every command is capability-checked in Java before
  any handler runs (JDesk `CapabilityEngine`, verified in the framework audit). The editor grants a
  single `editor:core` capability to the `main` window; no command is public.
- **Agent ↔ editor (MCP)**: agents reach the editor only through the MCP server, which is
  loopback-bound and authenticated. Agents never get a JDesk evaluate surface.
- **Production vs test**: the JDesk automation endpoint (in-page `/evaluate`) exists only in the
  e2e build. The production package excludes the `jdesk-automation` module — verified by
  `PKG-03-no-automation` (run 20260713T120727Z-337d).

## Path safety

`PathService` is the single canonicalization + containment choke point. Every path that reaches the
document store, atomic saver, search, git, terminal, or an MCP tool passes through it.

- Rejects `..` traversal, absolute paths outside the workspace, and symlink escape (following a
  symlink to a real target outside the root). Errors never echo absolute paths.
- **Tests**: `PathServiceTest` (10 cases incl. real symlink-escape); over MCP,
  `McpServerIT.pathTraversalIsRejectedWithBoundaryError` (an agent's `../escape.txt` →
  `WORKSPACE_BOUNDARY_VIOLATION`).

## Tokens & the MCP server

- Binds `127.0.0.1` on an ephemeral port; a 256-bit bearer token is generated per run and required
  on every request (constant-time comparison). Unauthenticated requests get `401` —
  `McpServerIT.rejectsUnauthenticatedRequest`.
- The discovery file is written with owner-only (`rw-------`) permissions; the token is never placed
  on a command line (env var for Codex, config-file header for Claude).
- `Origin` headers are checked; only loopback origins are accepted (DNS-rebinding defense).
- No JDesk `evaluate` is exposed through MCP.

## Approvals

Destructive MCP tools are flagged (`destructive`) in the tool catalog for an approval gate. The
current tool set is non-destructive (create/edit/save/read); `file_delete` and risky
`terminal_write` land with the approval UI (tracked in docs/STATUS.md as remaining).

## CSP

Production CSP: `default-src 'self'; script-src 'self'; worker-src 'self' blob:;
style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'self';
object-src 'none'; base-uri 'none'; frame-ancestors 'none'`. `script-src` stays `'self'` and
**`unsafe-eval` is never present** — the Phase-0 gate proves `eval()` and `new Function()` both
throw (GATE-09). `style-src 'unsafe-inline'` is required by Monaco's dynamic `<style>` injection and
is acknowledged via `-Djdesk.security.acknowledgeUnsafeCsp=true` (style-only relaxation).

## Secret redaction

The evidence harness `Redactor` masks the automation and MCP bearer tokens plus common token shapes
before writing any artifact — `RedactorTest` (incl. a planted-secret property test).

## Threat model (summary)

| Threat | Control | Evidence |
|---|---|---|
| Agent reads/writes outside the workspace | PathService containment + symlink resolution | PathServiceTest, McpServerIT |
| Unauthenticated local process calls the editor | Per-run bearer token, loopback bind, 401 | McpServerIT |
| Token leaks into logs/artifacts | Redactor (literal + pattern) | RedactorTest |
| Arbitrary code execution via CSP | `script-src 'self'`, no `unsafe-eval` | GATE-09 |
| Test automation shipped to users | automation excluded from production package | PKG-03 |
| Silent overwrite of external/dirty changes | version conflict + lease + watcher conflict | DocumentStoreTest, E2E-11 |
