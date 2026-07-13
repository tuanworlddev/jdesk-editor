# JDesk Editor

An agent-native desktop code editor built on the [JDesk](https://jdesk.dev) framework (Java 25 core
+ system WebView). Its defining feature: coding agents such as Codex and Claude Code drive the
**real running editor** through semantic MCP tools — creating files, editing through Monaco, and
saving — rather than inspecting screenshots or guessing coordinates. It also works as an ordinary
code editor with no agent involved.

> **Status:** the agent-native core is built and proven with live evidence (a real Claude session
> editing the running editor through MCP, the change appearing live in Monaco). Several phases
> remain. See **[docs/STATUS.md](docs/STATUS.md)** for an honest, evidence-linked breakdown.

## Requirements

- **macOS** (arm64 verified). Windows/Linux are not yet verified — see docs/STATUS.md.
- **JDK 25** (`java --version` → 25.x) with `jlink`/`jpackage`.
- **Node 20+** and npm.
- The JDesk framework checked out at `../JDESK/JDesk` (pinned SHA in `gradle.properties`; consumed
  as a Gradle composite build).
- For live agent features: an authenticated **Claude Code** (`claude`) and/or **Codex** (`codex`)
  CLI on your PATH.

## Build

```bash
# One-time: build the frontend and vendor the JDesk TS client
(cd ui && npm install && npm run build)

# Compile everything and run the headless unit/integration tests
./gradlew :editor-core:test :agent-mcp:test :evidence-core:test :app:test
```

## Run the editor

```bash
# Launches the native window (Monaco over jdesk://app), optionally opening a folder
./gradlew :app:run -PjdeskPlatform=macos --args="--workspace /path/to/your/project"
```

- Click a file in the Explorer to open it in Monaco; edit; **⌘S** to save.
- One Monaco instance backs all tabs; the Java core holds the authoritative buffer and writes saves
  atomically.

## Agent setup (MCP)

Run the editor with the MCP server enabled, then point an agent at the generated config:

```bash
./gradlew :app:run -PjdeskPlatform=macos -PjdeskAutomation=true --args="--workspace /path/to/project"
# The app prints:  MCP-READY http://127.0.0.1:<port>/mcp
# and writes an agent config to  app/build/mcp/mcp-config.json

# Drive it with a real agent (its edits appear live in the running editor):
claude -p "Use the jdesk_editor tools to create src/hello.txt and write a greeting" \
  --mcp-config app/build/mcp/mcp-config.json \
  --allowedTools "mcp__jdesk_editor__file_create" "mcp__jdesk_editor__editor_apply_workspace_edit" "mcp__jdesk_editor__editor_save"
```

The MCP server binds to loopback only, authenticates every request with a per-run bearer token, and
scopes all file access to the open workspace.

## Verification & evidence

All acceptance results are backed by machine-checked evidence runs and listed in
[VERIFICATION.md](VERIFICATION.md). The evidence harness refuses to record a PASS without
run-produced proof and refuses to run against a drifted framework checkout.

```bash
./gradlew :evidence-core:installDist
evidence-core/build/install/evidence-core/bin/evidence-core verify   # audit all runs
```

## Layout

| Module | Role |
|---|---|
| `editor-api` | Wire DTOs, `WorkspacePath`, error codes (pure JDK) |
| `editor-core` | Path safety, document model, leases, atomic save, file tree |
| `agent-mcp` | Authenticated Streamable-HTTP MCP server + editor tools |
| `app` | JDesk app: typed `@DesktopCommand` facades, MCP wiring, window |
| `ui/` | React + TypeScript + Vite + Monaco frontend |
| `evidence-core` | Anti-fabrication evidence harness (spec §25) |
| `e2e/gate-app` | Phase-0 Monaco worker gate + semantic proof app |

## Limitations

Binary/hex editing, VS Code extension compatibility, remote development, collaborative editing, a
general debugger, and OS mouse control are explicit non-goals. Cross-platform support beyond macOS
is not yet verified. See docs/STATUS.md for the full remaining-work list.
