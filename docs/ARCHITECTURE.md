# Architecture

Modules, trust boundaries, threads, protocols, and state ownership.

## Module graph (acyclic)

```
editor-api ── editor-core ── git-service
    │              │      ╲── agent-mcp ── app ── ui/ (React/Monaco)
    │              │      ╱                 │
    └── terminal-service ─┘        language-services
evidence-core (standalone)   e2e/gate-app (standalone)
```

- **editor-api** — pure-JDK wire DTOs, `WorkspacePath`, `EditorErrorCode`. Bottom of the graph.
- **editor-core** — path safety (`PathService`), authoritative document model (`DocumentStore`,
  `EditorDocument`, leases, `AtomicSaver`), `FileTree`, `SearchService`, `Hashing`, `TextEdits`.
- **agent-mcp** — the loopback Streamable-HTTP MCP server, tool catalog, and `EditorBridge`.
- **git-service** / **terminal-service** / **language-services** — system Git, PTY backpressure,
  and LSP4J-based language servers.
- **app** — JDesk application: typed `@DesktopCommand` facades, MCP wiring, terminal/watcher/agent
  managers, window lifecycle.
- **ui/** — React + Monaco frontend, consuming the generated TypeScript bindings.
- **evidence-core** — the anti-fabrication verification harness.

## Trust boundaries

1. **WebView ↔ Java** — the WebView is untrusted. Commands are capability-checked in Java before any
   handler runs. Java holds the authoritative document buffers; the frontend syncs deltas.
2. **Agent ↔ editor** — agents reach the editor only via the authenticated loopback MCP server. No
   JDesk evaluate surface is exposed.
3. **Production vs test** — the JDesk automation endpoint exists only in the e2e build; production
   packages exclude it (verified).

## Threads

- **UI thread** — window/WebView work only (JDesk `UiDispatcher`); never blocked.
- **Command handlers** — each runs on its own virtual thread (JDesk).
- **MCP server** — virtual-thread-per-request on `com.sun.net.httpserver`.
- **PTY / watcher / LSP / agent** — background threads; output is batched/coalesced onto the bounded
  event channel, never one event per byte.

## Protocols

- **IPC** — JDesk envelope v1: JS→Java capped (1 MiB/envelope, 256 KiB/string, 30 s); Java→JS
  uncapped. Typed via codegen.
- **MCP** — JSON-RPC 2.0 over Streamable HTTP (rev 2025-11-25), bearer-authenticated.
- **Agents** — Claude Code stream-json (embedded + external); Codex app-server JSONL (handshake proven).
- **LSP** — LSP4J over stdio.

## State ownership

The Java `DocumentStore` is the single source of truth for open documents (content, version, hashes,
dirty, lease, external-change state). Monaco models are synchronized views. Human edits flow up as
deltas under optimistic concurrency; agent edits (via MCP) commit in Java and push authoritative
content down to the live model. The filesystem watcher reconciles external changes against the same
store (reload clean, conflict dirty).
