# Agent Adapters

How the editor integrates coding agents (spec §14–§15).

## Claude Code (embedded + external) — implemented, proven live

- **External MCP client**: any Claude session pointed at the editor's `--mcp-config` drives the
  editor tools. Proven: `docs/verification/runs/20260713T113238Z-9501`.
- **Embedded (app-managed)**: `ManagedClaudeSession` spawns the user's authenticated `claude` CLI as
  a subprocess pointed at the editor's own loopback MCP config, so the agent's edits go through the
  editor and appear live. Driven by the typed `agent.startClaude/status/interrupt` commands. Stream
  parsing: `system/init` (session id), `assistant` `tool_use` (tool calls), `result` (success +
  text). Proven: `…122144Z-df79`.
- Flags used: `-p --mcp-config <file> --allowedTools mcp__jdesk_editor__* --output-format stream-json
  --verbose --max-turns N`. (`--verbose` is required with `-p --output-format stream-json`.)

## Codex — protocol proven, embedded turn pending

- `codex app-server` initialize handshake proven live: `…103004Z-5b9b` (returns platformOs=macos).
- The protocol schema is snapshotted under `test-fixtures/agent-protocol/codex/schema/`.
- Codex supports streamable-HTTP MCP servers natively (`mcp_servers.<name>.url` +
  `bearer_token_env_var`), so the same editor MCP server serves it. The embedded app-managed Codex
  turn (spawning `codex app-server`, JSONL framing, turn lifecycle, approval bridging) is not yet
  built — see docs/STATUS.md.

## Instruction injection

Agents are told to use the `jdesk_editor` MCP tools for writes (via the prompt / allowedTools). The
editor never modifies the user's global agent config; MCP config is per-session.
