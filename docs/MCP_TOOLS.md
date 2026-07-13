# MCP Tools

The editor exposes an authenticated loopback MCP server (spec §13). Tool names use underscores
(Anthropic's tool-name grammar rejects dots); the dotted spec aliases are also accepted on
`tools/call`. Every mutation returns the spec §13.2 operation envelope; errors carry §13.3 codes as
an `isError` result.

## Transport & auth

- Streamable HTTP, JSON-RPC 2.0, protocol revision `2025-11-25`. Single `POST /mcp` endpoint
  (`GET` → 405). Loopback bind, ephemeral port.
- `Authorization: Bearer <token>` required (401 otherwise); 256-bit per-run token; owner-only
  discovery file; loopback `Origin` check.

## Tools (12 implemented of the 18 spec tools)

| Tool (underscore) | Dotted alias | Purpose |
|---|---|---|
| `workspace_get_state` | `workspace.get_state` | Active workspace root + entries |
| `workspace_list` | `workspace.list` | Lazy directory children |
| `workspace_search` | `workspace.search` | File-path substring search |
| `file_create` | `file.create` | Create + open a file (makes parent dirs) |
| `editor_open` | `editor.open` | Open a document (uri, version, hash, content) |
| `editor_apply_workspace_edit` | `editor.apply_workspace_edit` | Apply text edits through the editor (appears live) |
| `editor_save` | `editor.save` | Atomic save; returns version + disk hash |
| `editor_get_diagnostics` | `editor.get_diagnostics` | Current diagnostics for a document |
| `terminal_open` | `terminal.open` | Open a real PTY; returns terminal id |
| `terminal_write` | `terminal.write` | Write input to a terminal |
| `terminal_read` | `terminal.read` | Read + clear output; liveness + exit code |
| `terminal_close` | `terminal.close` | Close a terminal |

**Not yet implemented** (spec §13.2 full set): `ui.snapshot/activate/focus`, `file.rename/delete`
(with approval), `editor.set_selection`, `agent.wait_for_state`.

## Mutation envelope

```json
{ "operationId": "op-…", "status": "COMMITTED",
  "documentVersions": { "file:///…/App.tsx": 13 },
  "summary": "Applied 2 edit(s) to src/App.tsx" }
```

## Error codes (spec §13.3)

`INVALID_ARGUMENT, CAPABILITY_DENIED, APPROVAL_REQUIRED, WORKSPACE_BOUNDARY_VIOLATION,
STALE_UI_STATE, DOCUMENT_VERSION_CONFLICT, EDIT_LEASE_CONFLICT, TARGET_NOT_FOUND,
TARGET_NOT_ACTIONABLE, AGENT_NOT_AVAILABLE, PROCESS_FAILED, TIMEOUT, CANCELLED, INTERNAL_ERROR`.

## Example: an agent creates, edits, and saves

```
tools/call file_create {"relPath":"src/hello.txt"}
tools/call editor_apply_workspace_edit {"relPath":"src/hello.txt",
  "edits":[{"startLine":1,"startColumn":1,"endLine":1,"endColumn":1,"text":"Hello\n"}]}
tools/call editor_save {"relPath":"src/hello.txt"}
```

Proven live: `docs/verification/runs/20260713T113238Z-9501` (external Claude) and
`…122144Z-df79` (embedded, editor-managed Claude).
