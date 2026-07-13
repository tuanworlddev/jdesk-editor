# Monaco Worker Strategy (Phase-0 gate result)

**Pinned strategy: `same-origin-esm`** — proven on the first rung of the fallback ladder.

The Phase-0 gate (`e2e/gate-app`) settled the single genuinely-unverifiable-from-source risk of
this project: whether Monaco Editor's web workers load and function on the production
`jdesk://app` custom scheme on macOS WKWebView. They do.

## What was proven (macOS 26.5.1, WKWebView WebKit 21624)

Evidence run `20260713T105334Z-d829` (category `gate`), all 10 spec §6.1 items PASS:

| Item | Result |
|---|---|
| Base editor worker starts | diff editor computed line changes off-thread |
| TypeScript/JavaScript worker starts | `getTypeScriptWorker()` proxy round trip returned diagnostics |
| JSON worker starts | schema-validation markers produced |
| Syntax highlighting | tokenization + DOM span classes |
| Completion | `arr.` completion includes `push` (39 entries) |
| Diagnostics | TS error 2322 surfaced as a Monaco marker |
| Copy/paste/selection/keyboard/undo/redo | selection read, input focused, typed edit + undo + redo |
| Worker asset MIME | 3/3 workers served as `text/javascript; charset=utf-8` |
| CSP correct, no unsafe eval | policy header present, `eval()`/`new Function()` both throw |
| No worker/module-load errors | 0 genuine errors (1 benign Monaco `Canceled` rejection, classified) |

## The strategy

- **Bundling:** Vite with `worker.format: 'es'`; Monaco workers imported via `?worker` and wired
  through `self.MonacoEnvironment.getWorker`. Every worker resolves to a same-origin
  `jdesk://app/assets/*.worker.js` file — no blob:, no CDN, no AMD loader, no `eval`.
- **Serving:** the built `web/` bundle is served from the app jar via `ClasspathAssetSource`
  (`-Djdesk.assets.module`), identical to a packaged app.
- **CSP:** `script-src 'self'` (workers ride this), `worker-src 'self' blob:` (blob-fallback
  headroom, unused), `style-src 'self' 'unsafe-inline'` (Monaco injects dynamic `<style>` nodes) —
  requires `-Djdesk.security.acknowledgeUnsafeCsp=true`. **Never `unsafe-eval`.**
- **Hashing:** frontend uses `@noble/hashes` SHA-256 (no `crypto.subtle` on the macOS custom
  scheme); proven byte-equivalent to Java `MessageDigest` SHA-256 by gate item S3-JAVA.

## Fallback ladder (not needed on macOS)

The ladder remains available if a future platform rejects rung 1:
1. `same-origin-esm` — **chosen** (Windows/Linux register the scheme secure+CORS, so ≥ as likely
   to work there as on macOS, where it already works).
2. `blob` workers + CSP `worker-src 'self' blob:` (already permitted).
3. authenticated loopback asset server (last resort; deviates from JDesk's no-localhost ADR and
   would require explicit sign-off).

The editor's production UI (Phase 1+) reuses this exact configuration. Any change to the worker
bundling or CSP must re-run the gate.
