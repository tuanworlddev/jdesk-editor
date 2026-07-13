# JDesk Framework Audit

**Audited framework:** `dev.jdesk` (JDesk) at `/Users/rupphi/Projects/JDESK/JDesk`
**Pinned commit:** `bf91bff70a1705b7c950faa01b8b26280e19e165` (tag `v0.1.2`), working tree clean
**Consumed as:** Gradle composite build (`includeBuild`) — JDesk is not yet on Maven Central.
**Audit host:** macOS 26.5.1 (Darwin 25.5.0) arm64, JDK 25.0.3 (Homebrew), Gradle 9.6.1.

This document records what the JDesk framework actually provides, verified against its source
and its own test suite executed on this machine. Where documentation and code disagreed during
the audit, code was treated as authoritative (spec §6). Every capability the editor relies on is
cited to a concrete source path. Evidence for each executed command lives under
`artifacts/test-runs/<runId>/` (gitignored) with tamper-evident stubs cited in `VERIFICATION.md`.

## 1. Provenance & consumption

- Version: `0.1.2` (`gradle.properties` `version=0.1.2`; tags `v0.1.0..v0.1.2`).
- The editor pins the exact SHA in `gradle.properties` (`jdeskPinnedSha`). The evidence harness
  (`evidence-core`) refuses to start any verification run if the JDesk checkout is not at that
  clean SHA, so no result can silently attribute to a drifted framework.
- Coordinates used: `dev.jdesk:jdesk-api`, `dev.jdesk:jdesk-runtime`, `runtimeOnly
  dev.jdesk:jdesk-platform-macos`, plus the `dev.jdesk.application` Gradle plugin and
  `dev.jdesk:jdesk-codegen` annotation processor (all `0.1.2`).
- Framework is pure-Java FFM (Project Panama) — no JNI, no bundled native binaries.

## 2. Commands executed & results

| Test ID | Command | Exit | Outcome | Evidence run |
|---|---|---|---|---|
| JDESK-CHECK | `gradlew clean check` (full suite) | 1 | **FAIL** (known cause, §4) | `20260713T102612Z-ad0d` |
| JDESK-CHECK-MACOS | `gradlew check -x :modules:jdesk-platform-windows:test` | 0 | **PASS** | `20260713T102748Z-dc23` |
| JDESK-NATIVE-SMOKE | `gradlew --no-configuration-cache :test-apps:native-smoke:run -PjdeskPlatform=macos` | 0 | **PASS** | `20260713T103029Z-6ff9` |
| JDESK-SECURITY-PROBE | `gradlew --no-configuration-cache :test-apps:security-probe:run -PjdeskPlatform=macos` | 0 | **PASS** | `20260713T103116Z-b8b4` |

The **security-probe** exercises JDesk's §17.6 suite on the real WebView (CSP enforcement,
origin/nonce lifecycle, navigation/popup restrictions, iframe isolation) and produced its own
`status: PASSED` evidence — the trust boundary the editor's own security model builds on.

**native-smoke** is the load-bearing result: it launches a **real WKWebView window** on this
machine and exercises the full IPC bridge (typed echo, Java→JS events, cancellation, capability
denial, oversize-payload rejection, 100 concurrent invokes, asset 200/404/traversal-reject,
secondary windows, 2 GiB binary streaming with backpressure, PTY, file-watch, upload, clean
shutdown). JDesk's own evidence manifest for the run reports:

- `status: PASSED`, `exitCode: 0`
- `platformProviderId: macos-wkwebview`
- `webViewVersion: WebKit 21624 (21624.2.5.11.4)`
- `frameworkVersion: 0.1.2`
- The automation endpoint came up (`JDESK-AUTOMATION port=… descriptor=…`), confirming the
  loopback + bearer-token automation infrastructure the editor's E2E harness depends on.

## 3. Relied-upon capabilities → source evidence

All paths are under `/Users/rupphi/Projects/JDESK/JDesk`.

| Capability the editor uses | Source of truth |
|---|---|
| App builder / lifecycle | `modules/jdesk-api/src/main/java/dev/jdesk/api/JDeskApplication.java`, `ApplicationHandle.java`, `WindowConfig.java`, `LifecycleListener.java` |
| Typed commands + capabilities | `modules/jdesk-api/.../DesktopCommand.java`, `RequiresCapability.java`, `PublicDesktopCommand.java`; enforcement `modules/jdesk-runtime/.../capability/CapabilityEngine.java` called from `.../ipc/CommandDispatcher.java` before deserialization |
| TS + Java binding codegen | `modules/jdesk-codegen/.../DesktopCommandProcessor.java` (+ `JavaEmitter`, `TsEmitter`); wired by `modules/jdesk-gradle-plugin/.../TsBindingArguments.java` |
| Frontend client | `js/jdesk-client/src/index.ts` — `invoke`, `invokeStream`, `on`, `emit`, `JDeskError` |
| Events Java→JS (bounded 256/window) | `modules/jdesk-runtime/.../ipc/EventQueue.java`, `EventOverflowPolicy.java`, `IpcLimits.java` (REJECT default; `maxQueuedEventsPerWindow=256`; lower-only) |
| IPC limits (1 MiB env / 256 KiB string / 128 in-flight / 30 s) | `modules/jdesk-runtime/.../ipc/IpcLimits.java`, `json/JsonLimits.java`, `EnvelopeCodec.java` |
| Bulk JS→Java upload channel | `modules/jdesk-api/.../AssetRoute.java` (`Request.body` = exact POST bytes; `jdesk.assets.maxUploadBytes` default 64 MiB) |
| Production origin `jdesk://app` + MIME | `modules/jdesk-runtime/.../boot/JDeskRuntime.java` (`APP_ORIGIN`), `assets/AssetResolver.java`, `assets/MimeTypes.java` (`.js`→`text/javascript`, `.wasm`→`application/wasm`) |
| CSP (response header, customizable) | `modules/jdesk-runtime/.../assets/CspValidator.java` (`DEFAULT_CSP`, release screen), `modules/jdesk-api/.../Csp.java`; ack flag `jdesk.security.acknowledgeUnsafeCsp` in `boot/RuntimeBootstrap.java` |
| PTY | `modules/jdesk-api/.../PtyHandle.java`, `PtySpec.java`; `modules/jdesk-runtime/.../pty/PtyManager.java` + Mac backend |
| File watch | `modules/jdesk-api/.../ApplicationHandle.java` `watchFiles(...)`; `FileWatchEvent.java` |
| Automation (E2E, test-only) | `modules/jdesk-automation/.../AutomationServer.java` (loopback HTTP, Bearer, `/windows` `/evaluate` `/snapshot` `/console` `/input`); console capture `modules/jdesk-webview-spi/.../InitScripts.java` |
| Packaging | `modules/jdesk-gradle-plugin/.../JDeskApplicationPlugin.java` + `tasks/*` (`jdeskRuntimeImage`→`jdeskPackage`→`jdeskInstaller`→`jdeskNativeSmokeTest`); arg builders `modules/jdesk-packager/.../*` |
| Evidence mechanics (adapted, not depended on) | `modules/jdesk-testkit/.../evidence/EvidenceRun.java`, `EvidenceVerifier.java` |

## 4. Gaps found

1. **`gradlew clean check` is not green on macOS.** Four `WindowsMenuTest` cases in
   `:modules:jdesk-platform-windows:test` fail with `ExceptionInInitializerError` /
   `NoClassDefFoundError` because `dev.jdesk.platform.windows.WindowsMenu`'s static initializer
   calls `SymbolLookup.libraryLookup` to load Windows DLLs (user32), which cannot succeed on
   macOS. This is a **test-isolation defect in a module the editor never loads on macOS**
   (macOS uses `jdesk-platform-macos`). Per the audit failure policy this is recorded as a
   **known issue, not fixed** (scope discipline); the macOS-relevant suite
   (`check -x :modules:jdesk-platform-windows:test`) is green (JDESK-CHECK-MACOS, exit 0).
2. **macOS custom scheme is not a secure context.** `jdesk://app` is registered secure+CORS on
   Windows/Linux but WKWebView exposes no API to do so, so `window.crypto.subtle` and
   `crossOriginIsolated`/`SharedArrayBuffer` are unavailable on macOS. Editor impact: the
   frontend hashes with a pure-JS SHA-256 (`@noble/hashes`) rather than WebCrypto. This is the
   single behavior the Phase-0 Monaco gate must confirm empirically (same-origin ESM workers on
   WKWebView). Source: `docs/platform/prerequisites.md`, `MacWebView.java`.
3. **Default CSP has no `worker-src` and no `blob:`.** Same-origin ESM workers ride
   `script-src 'self'` (expected to work); blob workers need `worker-src 'self' blob:`, which the
   release validator permits. Monaco also injects dynamic `<style>` nodes, so the editor's CSP
   will need `style-src 'self' 'unsafe-inline'`, requiring `-Djdesk.security.acknowledgeUnsafeCsp=true`
   (style-only relaxation; `script-src 'self'` retained; **never `unsafe-eval`**).
4. **No renderer backpressure on PTY output.** `openPty` pushes bytes via a callback with no flow
   control to the WebView; the editor must batch/credit output itself to stay under the
   256-event queue (handled by the Phase-5 `terminal-service` design).
5. **Event queue overflow policy is not app-configurable** (`RuntimeOptions.fromSystemProperties`
   always returns production defaults: REJECT, `IpcLimits.DEFAULTS`). The editor self-throttles
   its outbound dispatcher rather than relying on DROP_OLDEST/COALESCE.
6. **Typed TS bindings have no shipped reference consumer.** Codegen + `jdesk-client` are real and
   unit-tested, but every JDesk example uses a hand-written bridge. The editor is the first real
   consumer (proven in the Phase-0 gate; fallback: thin hand-written typed wrappers over
   `invoke()`).

## 5. Required framework fixes

**None required for the macOS editor.** Every gap above is handled on the editor side without a
framework change. The only condition that would justify a JDesk patch is the Phase-0 Monaco gate
failing *inside* the scheme handler (a MIME/streaming defect) — in which case the fix would be the
smallest correct change plus tests, committed separately in the JDesk repo and re-pinned here with
its SHA recorded. As of this audit that has not been necessary.
