# Testing

## Tiers & commands

- **Unit** (headless): `./gradlew test` — path safety, document model, edits/hashes, search,
  terminal backpressure, MCP schemas, redaction, evidence-harness honesty.
- **Integration** (real processes): `./gradlew integrationTest` — real Git, real LSP
  (typescript-language-server + pyright), MCP over real loopback HTTP.
- **Native E2E** (real app + WKWebView): `evidence-cli app-run …` driving the launched editor via
  the automation endpoint + MCP.
- **Live agent** (real Claude quota): `scripts/live-*.sh` — external and embedded Claude editing the
  running editor.
- **Gate**: `evidence-cli app-run gate … -- gate-app --gate monaco|semantic`.
- **Packaging / performance**: `scripts/package-acceptance.sh`, `scripts/perf-measure.sh`.

## Evidence & honesty

Every acceptance run goes through `evidence-core`, which refuses a PASS without run-produced
evidence, refuses to run against a drifted JDesk checkout, and requires a real WebView backend for
native categories. `evidence-cli verify` audits all runs; `evidence-cli report` regenerates
`VERIFICATION.md`. Mocks/fixtures back parser tests only, never live-integration claims.
