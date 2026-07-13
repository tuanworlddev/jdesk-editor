# Agent instructions for this repository

When working in JDesk Editor as a coding agent connected via the `jdesk_editor` MCP server:

- Use the editor MCP tools for ALL file operations: `file_create`, `editor_open`,
  `editor_apply_workspace_edit`, `editor_save`. Do not write files directly — edits made through the
  tools appear live in the running editor and are versioned/hashed correctly.
- Read state with `workspace_get_state`, `workspace_list`, `workspace_search`, `editor_open`.
- Use `terminal_open`/`terminal_write`/`terminal_read`/`terminal_close` for shell commands.
- Paths are workspace-relative; traversal outside the workspace is rejected.
