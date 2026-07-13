# JDesk Editor — Agent-Native Desktop Code Editor

**Specification:** 1.0  
**Status:** Implementation-ready  
**Primary package:** dev.jdesk.editor  
**Framework:** JDesk, Java 25 core, system WebView  
**Primary release platforms:** macOS and Windows  
**Compatibility platform:** Linux with mandatory native smoke testing

---

## 1. Product statement

Build a desktop code editor inspired by Visual Studio Code using JDesk. Its defining feature is that coding agents such as Codex and Claude Code can interact with the real running editor through semantic actions:

- activate the New File button;
- enter a filename;
- select and open a file in Explorer;
- focus the code editor;
- move the visible agent pointer;
- place the Monaco cursor at an exact logical position;
- stream edits into Monaco with configurable human-like presentation;
- save, run commands, inspect diagnostics, and react to approvals.

Agents must not inspect screenshots to guess coordinates. Agents must not call click(x, y). The editor exposes a structured semantic UI model and typed tools. The application resolves semantic targets to the current DOM, performs the same application action used by a human, and renders pointer and typing animations.

The application must remain useful as a normal code editor when no agent is active.

---

## 2. Fixed product decisions

These decisions are requirements, not unresolved questions.

1. JDesk Editor launches and manages Codex and Claude sessions inside the application.
2. External Codex, Claude Code, or any MCP-compatible agent may also connect to the editor.
3. Agent writes are semantic-first: create, open, edit, and save should go through editor MCP tools and Monaco.
4. A filesystem-watcher compatibility mode visualizes changes made directly by an external agent.
5. The visible agent pointer is an in-app overlay. The application must not move the operating-system pointer.
6. Three presentation modes are required: CINEMATIC, LIVE, and INSTANT.
7. A file or edit range has only one active writer. Human input may interrupt an agent and take ownership.
8. Reuse the user's authenticated Codex CLI and Claude Code installations for version 1. API-key providers are future work.
9. macOS and Windows are primary. Linux must compile, package where supported, and pass a real WebKitGTK smoke test before cross-platform completion is claimed.
10. Monaco Worker compatibility on the JDesk production origin is a phase-zero release gate.
11. VS Code extension compatibility and a general debugger are not version-1 requirements.
12. No mocked result may be presented as proof that Codex, Claude, JDesk WebView, MCP, PTY, LSP, packaging, or native automation works.

---

## 3. Goals

### 3.1 Editor goals

- Open a folder as a workspace.
- Render a virtualized file Explorer.
- Create, rename, delete, move, and open files and folders.
- Open many files in tabs while using one Monaco editor instance with multiple models.
- Support dirty state, save, save all, close confirmation, undo, and redo.
- Provide workspace search and replace.
- Provide Problems, Output, and Terminal panels.
- Provide Git status and diff views.
- Provide language diagnostics and common language features through LSP.
- Persist layout, tabs, recent workspaces, themes, and settings.

### 3.2 Agent goals

- Start, resume, interrupt, and steer an agent session.
- Render streaming messages, tool activity, commands, edits, approvals, and errors.
- Expose typed MCP tools for workspace, semantic UI, editor, diagnostics, and terminal operations.
- Let agents interact without screenshots, OCR, external mouse automation, or guessed selectors.
- Record a deterministic action log for debugging and replay.
- Allow a human to stop the agent immediately.
- Detect and safely handle human/agent edit conflicts.

### 3.3 Experience goals

- Keep the UI smooth while agents, LSP servers, terminals, and file watchers are active.
- Make agent actions visually understandable without unnecessarily delaying work.
- Allow multiple visual pointer identities, while version 1 permits only one writer per document.
- Clearly show thinking, reading, editing, running, waiting for approval, completed, failed, and interrupted states.

---

## 4. Non-goals for version 1

- Binary or hex editing.
- Full VS Code extension API compatibility.
- Marketplace compatibility.
- Remote development over SSH or containers.
- Collaborative multi-user editing.
- General Debug Adapter Protocol UI.
- Operating-system mouse control.
- Screenshot-based agent control.
- Arbitrary JavaScript evaluation exposed to production agents.
- Claiming identical IDE support for every language.

---

## 5. Required technology stack

### 5.1 Desktop and Java core

- Java 25.
- The current locally verified JDesk version.
- Gradle Kotlin DSL and Gradle Wrapper.
- JPMS modules wherever compatible with required libraries.
- Virtual threads for blocking I/O and process streams.
- JDesk UiDispatcher only for bounded WebView and window work.
- The JSON implementation already standardized by JDesk, or Jackson if none exists.
- java.nio.file for paths, file channels, atomic moves, and watch services.

### 5.2 Web frontend

- React with TypeScript.
- Vite production build.
- Tailwind CSS.
- Monaco Editor.
- xterm.js with Fit and WebGL addons, plus DOM renderer fallback.
- A lightweight store such as Zustand. Do not store each typed character in React state.
- A virtualized tree/list implementation for Explorer and search results.

### 5.3 Protocols and integrations

- MCP for external agent-to-editor tools.
- Codex App Server JSON-RPC over stdio for embedded Codex.
- Claude Code structured streaming protocol for embedded Claude.
- LSP and JSON-RPC for language services; Eclipse LSP4J is preferred.
- PTY through pty4j or a proven equivalent supporting Unix PTY and Windows ConPTY.
- System Git CLI through argument-safe ProcessBuilder. Never concatenate untrusted shell commands.

All versions must be pinned through lockfiles or a version catalog. The word “latest” must not remain in committed build files.

---

## 6. Phase-zero JDesk audit

Before implementing the product, inspect the actual JDesk source and run its real test suite. Documentation is not proof of implementation.

Verify the existence and behavior of:

- typed command registration and generated TypeScript bindings;
- frontend-to-Java invoke and Java-to-frontend events;
- capability enforcement;
- bounded event queues and overflow behavior;
- binary streaming with backpressure;
- native automation endpoints and authentication;
- window and WebView lifecycle;
- production asset origin and CSP;
- platform-specific UI dispatch;
- macOS WKWebView, Windows WebView2, and Linux WebKitGTK providers.

Create **docs/JDESK_AUDIT.md** containing:

- JDesk commit SHA or dependency version;
- concrete source paths for every relied-upon capability;
- commands executed;
- test results with exit codes;
- gaps found;
- required framework fixes.

If documentation and code disagree, code is authoritative. If a required feature is missing and JDesk source is in the workspace, implement the smallest correct framework change with tests in a separate commit. If source is unavailable, report a blocker instead of inventing an adapter.

### 6.1 Monaco production-origin gate

Create the smallest real JDesk application that loads Monaco from the same production asset mechanism used by a packaged application.

It must prove:

- base editor worker starts;
- TypeScript/JavaScript worker starts;
- JSON worker starts;
- syntax highlighting works;
- completion works;
- diagnostics work;
- copy, paste, selection, keyboard input, undo, and redo work;
- worker assets use the correct JavaScript MIME type;
- CSP permits required workers without unsafe arbitrary evaluation;
- no worker or module-load errors exist in the WebView console.

Run this gate on WebView2, WKWebView, and WebKitGTK. Prefer worker assets on the JDesk origin. If a platform rejects workers from the custom origin, use bundled self-contained Blob Workers with a CSP containing worker-src 'self' blob:. A loopback HTTP asset server is the final fallback and must retain per-run authentication and strict origin locking.

The main implementation must not begin until the current development platform passes this gate. Cross-platform completion must not be claimed until every platform has real evidence.

---

## 7. Architecture

    Codex App Server ─┐
                      ├─ Agent Adapters ─ Agent Session Service ─┐
    Claude Code ──────┘                                          │
                                                                 ├─ Action Coordinator
    External Agent ───── MCP Server ─ Capability/Approval Gate ──┘
                                                                        │
                              ┌─────────────────────────────────────────┴──────────────┐
                              │                                                        │
                        Java editor core                                      WebView frontend
                  Workspace / Documents / Git                           Semantic UI Registry
                  LSP / PTY / File Watcher                              Agent Pointer Actor
                  Transactions / Audit Log                              Monaco / xterm.js

The Action Coordinator is the only component that may convert agent intent into editor mutations.

### 7.1 Suggested repository structure

    jdesk-editor/
    ├── settings.gradle.kts
    ├── build.gradle.kts
    ├── gradle/
    ├── app/
    ├── editor-api/
    ├── editor-core/
    ├── agent-core/
    ├── agent-mcp/
    ├── agent-codex/
    ├── agent-claude/
    ├── language-services/
    ├── terminal-service/
    ├── git-service/
    ├── ui/
    ├── e2e/
    ├── test-workspaces/
    ├── docs/
    ├── artifacts/test-runs/
    ├── AGENTS.md
    └── README.md

Circular dependencies are forbidden. The UI may depend on generated TypeScript contracts but must not manually duplicate Java command schemas.

---

## 8. User interface and editor behavior

### 8.1 Layout

- Left activity bar: Explorer, Search, Source Control, Agent.
- Primary sidebar: current activity content.
- Editor area: tabs and Monaco.
- Optional right sidebar: agent conversation and activity timeline.
- Bottom panel: Problems, Output, Terminal.
- Status bar: branch, diagnostics, cursor, encoding, line ending, agent state.

The design may be inspired by VS Code but must use original styling and assets.

### 8.2 Documents and tabs

- One Monaco editor instance per editor group, not one per tab.
- One Monaco text model per open URI.
- Preserve view state, cursor, selection, scroll position, and folding per tab.
- Use Monaco edit APIs that preserve undo history.
- Never use setValue for ordinary edits.
- One logical agent workspace edit produces one undo group.
- Closing a dirty file requires save, discard, or cancel.
- External changes refresh clean documents and raise conflicts for dirty documents.

### 8.3 Explorer

- Virtualize large trees.
- Load children lazily.
- Preserve expansion state.
- Use workspace-relative semantic IDs.
- Support keyboard navigation and accessible roles.
- Ignore configurable build, dependency, and VCS directories by default.

### 8.4 Search

- Stream results incrementally.
- Respect ignore files and exclusions.
- Support text, regex, case sensitivity, include globs, and exclude globs.
- Multi-file replace must create a versioned transaction and preview changes.

---

## 9. Semantic UI Registry

The frontend must implement a production semantic registry independent of JDesk E2E automation.

### 9.1 Semantic node

    interface SemanticNode {
      id: string;
      role: string;
      name: string;
      description?: string;
      parentId?: string;
      visible: boolean;
      enabled: boolean;
      focused: boolean;
      selected?: boolean;
      expanded?: boolean;
      value?: string;
      actions: SemanticActionName[];
      stateVersion: number;
    }

Required ID examples:

    activity.explorer
    explorer.newFile
    explorer.newFolder
    file:src/main/java/dev/example/App.java
    tab:src/App.tsx
    editor:src/App.tsx
    panel.terminal
    terminal:main
    agent.stop
    approval:operation-id

IDs must not contain React indexes or styling classes. The same logical target must retain its ID after rerender, resize, theme change, virtualization, or sorting.

### 9.2 Semantic snapshot

The ui.snapshot operation returns:

- global revision;
- active workspace;
- active document URI and version;
- current focus;
- active modal;
- visible semantic subtree;
- relevant virtual nodes not currently mounted;
- agent state;
- allowed actions.

Every mutating semantic action accepts expectedRevision. A stale request returns STALE_UI_STATE with a compact current-state summary.

### 9.3 Action execution

An action must:

1. resolve the semantic target;
2. verify revision, visibility, enablement, and capability;
3. acquire any required edit lease;
4. enqueue visual pointer movement;
5. focus or reveal the real target;
6. invoke the same application command used by human interaction;
7. wait for the required state transition;
8. return an operation result with the new revision.

Do not expose arbitrary CSS selectors or JavaScript evaluation to agents.

---

## 10. Agent pointer and choreography

### 10.1 Pointer actor

- Render above the application with pointer-events disabled.
- Calculate target geometry inside the page at execution time.
- Never return target coordinates to agents.
- Use spring or eased interpolation.
- Recalculate geometry if layout changes during movement.
- Scroll targets into view before activation.
- Display agent name and color.
- Allow the human pointer to operate independently.

### 10.2 Choreography queue

Maintain separate states:

    ACCEPTED → COMMITTED → PRESENTED

- COMMITTED means authoritative application state changed.
- PRESENTED means visual animation completed.
- CINEMATIC waits for PRESENTED before the next visual action.
- LIVE may continue after COMMITTED, but visual lag must remain bounded.
- INSTANT minimizes presentation delay.
- Cancellation stops pending animation immediately without reverting committed edits.

---

## 11. Document versions, leases, and transactions

### 11.1 Document state

Each document tracks:

    uri
    workspaceRelativePath
    version
    contentHash
    diskHash
    encoding
    lineEnding
    dirty
    lastSavedAt
    externalChangeState
    activeLease

### 11.2 Edit lease

- Only one writer may hold a document or range lease.
- Human typing takes priority and may interrupt an agent lease.
- An agent action must contain the expected document version.
- Stale versions return DOCUMENT_VERSION_CONFLICT without partial writes.
- Multi-file changes acquire leases in deterministic URI order.

### 11.3 Semantic edit transaction

1. Validate workspace boundaries, capabilities, versions, and ranges in Java.
2. Reserve leases and create an editTransactionId.
3. Send the staged edit to the frontend.
4. Apply it to Monaco in the selected presentation mode.
5. Compute and return the resulting content hash.
6. Verify the hash in Java.
7. Atomically persist when save is requested.
8. Release leases and record the result.

If WebView or process failure happens before acknowledgement, reload authoritative disk state and mark the transaction interrupted. Never silently save an unverified partial buffer.

### 11.4 Atomic save

- Write to a temporary file in the same directory.
- Flush and close it.
- Use atomic replace where supported.
- Preserve required permissions.
- Never follow a symlink outside the workspace.
- Re-check canonical parent and disk hash before replacing.

---

## 12. Code presentation modes

### 12.1 CINEMATIC

- Apply actual Monaco edit batches.
- Animate by token, word, or small character group.
- Continuously reveal the active cursor.
- No maximum duration unless the user skips.
- Intended for demonstrations and recording.

### 12.2 LIVE

- Default mode.
- Apply real Monaco edits in frame-batched groups.
- Adapt speed to edit size.
- Target a maximum visual backlog of two seconds.
- Coalesce off-screen work.
- For large edits, animate the visible changed region and complete the remainder quickly while preserving one transaction and one undo group.

### 12.3 INSTANT

- Apply the workspace edit immediately.
- Move cursor and reveal the principal changed range.
- Show a brief changed-range decoration.

### 12.4 Rules

- No IPC call per character.
- No React state update per character.
- Use Monaco edit APIs directly.
- Batch rendering with requestAnimationFrame.
- Create undo stops at transaction boundaries only.
- The final content hash must be identical in all modes.

---

## 13. MCP editor server

Expose a local MCP server. For editor-launched agents, prefer authenticated loopback Streamable HTTP so the editor retains write authority. A stdio mode may also be provided for external clients.

### 13.1 Security

- Bind loopback only.
- Generate a high-entropy bearer token per run.
- Store discovery information with owner-only permissions.
- Avoid raw tokens in command-line history.
- Scope connections to explicit workspace roots.
- Validate canonical paths and symlinks server-side.
- Require approval for destructive operations and configured terminal commands.
- Do not expose JDesk evaluate functionality through MCP.

### 13.2 Required tools

    workspace.get_state
    workspace.list
    workspace.search
    ui.snapshot
    ui.activate
    ui.focus
    file.create
    file.rename
    file.delete
    editor.open
    editor.set_selection
    editor.apply_workspace_edit
    editor.save
    editor.get_diagnostics
    terminal.open
    terminal.write
    terminal.resize
    terminal.close
    agent.wait_for_state

Every mutation result contains:

    {
      "operationId": "...",
      "status": "COMMITTED",
      "uiRevision": 185,
      "documentVersions": {
        "file:///workspace/src/App.tsx": 13
      },
      "summary": "Created and opened src/App.tsx"
    }

### 13.3 Errors

At minimum:

    INVALID_ARGUMENT
    CAPABILITY_DENIED
    APPROVAL_REQUIRED
    WORKSPACE_BOUNDARY_VIOLATION
    STALE_UI_STATE
    DOCUMENT_VERSION_CONFLICT
    EDIT_LEASE_CONFLICT
    TARGET_NOT_FOUND
    TARGET_NOT_ACTIONABLE
    AGENT_NOT_AVAILABLE
    PROCESS_FAILED
    TIMEOUT
    CANCELLED
    INTERNAL_ERROR

Errors must be actionable without leaking secrets or unrelated absolute paths.

---

## 14. Codex integration

### 14.1 Runtime

- Discover the user-installed codex executable.
- Validate version and App Server capability.
- Launch codex app-server through ProcessBuilder with JSONL stdio.
- Perform the initialize handshake.
- Start, resume, fork, steer, interrupt, and archive threads when supported.
- Correlate request IDs and tolerate out-of-order completion.

### 14.2 Events

Map these into internal AgentEvent records:

    thread started/resumed
    turn started/completed
    agent message delta/completed
    reasoning summary delta
    command execution started/output/completed
    file change proposed/completed
    MCP tool call started/completed
    approval requested/resolved
    user input requested
    error
    interrupted

Completed items are authoritative. Deltas are presentation-only.

### 14.3 File changes

Project instructions must tell Codex to use JDesk Editor MCP tools for writes in visual mode. If Codex still produces a native file-change item:

- route the proposed diff through the Action Coordinator before approval; or
- decline it with a clear instruction to use the MCP tool;
- never apply the same change twice.

Compatibility mode may accept direct filesystem edits and visualize watcher diffs.

### 14.4 Approvals

Render native editor approval UI for:

- commands;
- file changes;
- additional permissions;
- destructive MCP tools;
- network access surfaced by Codex.

Correlate every prompt to thread, turn, and item IDs. Remove stale prompts when the server resolves or clears them.

---

## 15. Claude Code integration

- Discover the user-installed claude executable.
- Validate version and structured streaming flags.
- Use structured JSON streaming rather than terminal text scraping.
- Reuse the existing authenticated session.
- Support new session, resume, prompt, interrupt, streaming output, tool activity, available hook events, and completion status.
- Configure JDesk Editor MCP for the session without overwriting unrelated user settings.
- Give semantic editor tools priority for writes.
- Preserve redacted raw protocol events in diagnostic logs.
- If an installed version lacks a feature, report it and degrade gracefully; do not infer success from plain text.

Keep this integration behind the same AgentAdapter interface as Codex.

---

## 16. Watcher compatibility mode

The Java core watches the workspace for external changes.

- Coalesce events per canonical path.
- Re-read stable final file state.
- Compare hashes and compute text diffs off the UI thread.
- Update clean Monaco models and present changed regions.
- Show a conflict rather than overwriting dirty models.
- Avoid loops from observing the editor's own atomic saves.
- Preserve rename/delete semantics where possible; otherwise perform a safe rescan.

Watcher playback is compatibility behavior. It must not be described as proof that an agent semantically controlled the editor.

---

## 17. Terminal service

- Open a real PTY with the platform default shell.
- Support multiple sessions.
- Support UTF-8, resize, cwd, environment, exit status, terminate, and disposal.
- Stream output through a backpressured binary or batched channel, never one event per byte.
- Feed xterm.js input to Java in bounded chunks.
- Keep process I/O off the UI thread.
- Cap retained scrollback.
- Gate agent commands through capabilities and approvals.
- Display the same terminal output to the human and agent.

Required stress case: at least 10 MiB of mixed stdout/stderr without UI freeze, unbounded memory growth, lost completion, or corrupted UTF-8.

---

## 18. Language services

Version-1 priorities:

1. TypeScript/JavaScript.
2. Java.
3. Python.

Requirements:

- Manage language-server processes per workspace and language.
- Perform the LSP initialize lifecycle.
- Synchronize open, change, save, and close with versions.
- Render diagnostics in Monaco and Problems.
- Support hover, completion, definition, references, rename, formatting, and code actions when advertised.
- Apply LSP WorkspaceEdit through the Action Coordinator.
- Respect capabilities instead of assuming support.
- Show logs and crash/restart state.

A deterministic test server may test edge cases, but release evidence must also include one real TypeScript/JavaScript server and one real Java or Python server.

---

## 19. Git integration

- Discover system Git.
- Show branch, file status, staged and unstaged state, and repository root.
- Open text diffs in Monaco diff editor.
- Refresh after filesystem or Git changes.
- Use ProcessBuilder argument arrays.
- Do not add destructive reset or clean operations in version 1 unless explicitly approved.
- Git UI must work without an agent.

---

## 20. Persistence and recovery

Persist:

- recent workspaces;
- layout and panels;
- open tabs and view states;
- settings and theme;
- resumable agent session references;
- terminal metadata, not live processes;
- action-log metadata.

Do not persist bearer tokens, API secrets, or unredacted sensitive output in ordinary settings.

After a crash:

- detect unfinished edit transactions;
- never silently commit them;
- offer recovery for dirty buffers through a journal;
- validate journal roots and hashes;
- remove completed or expired entries.

---

## 21. Security

- Treat WebView as untrusted relative to Java capabilities.
- Deny commands by default and grant capabilities explicitly.
- Canonicalize every workspace path in Java.
- Reject traversal and symlink escape.
- Lock production navigation to the app origin.
- Deny unhandled popups.
- Use strict CSP; do not enable broad unsafe evaluation for Monaco.
- Redact secrets from logs and artifacts.
- Bound request bodies, in-flight work, queues, terminal buffers, and logs.
- Bind control servers to loopback and authenticate every connection.
- Require approval for delete, recursive operations, risky commands, and expanded filesystem or network access.
- Production packages must not accidentally include unrestricted JDesk E2E automation.

---

## 22. Performance requirements

Measure release builds, not only development builds.

- Pointer and typing presentation should sustain 60 FPS under normal workload.
- Do not run I/O, diffing, indexing, Git, LSP, PTY, or agent processing on the UI thread.
- Semantic logical acknowledgement target: p95 below 75 ms when no disk or process wait is required.
- Record actual warm file-open p50 and p95.
- Explorer must remain interactive while indexing at least 50,000 files.
- Open 50 tabs without creating 50 Monaco editors.
- Terminal stress output must not freeze the UI.
- Live presentation backlog must remain bounded.
- Queues must expose dropped, coalesced, rejected, and delayed metrics.

Create **docs/PERFORMANCE.md** with hardware, methodology, raw results, and limits.

---

## 23. Logging and observability

Structured records contain:

    timestamp
    level
    component
    operationId
    workspaceId
    agentProvider
    threadId
    turnId
    workspace-relative documentUri
    eventType
    durationMs
    outcome

Requirements:

- Correlate MCP call, semantic action, transaction, UI acknowledgement, save, and agent item.
- Redact secrets and tokens.
- Rotate logs.
- Provide diagnostics export.
- Preserve raw provider events only in debug mode after redaction.
- Never treat a fixture log line as proof that the real operation occurred.

---

## 24. Testing strategy

Mocks are allowed for deterministic unit tests but never as the only release evidence.

### 24.1 Unit tests

- Path canonicalization, traversal, and symlink escape.
- Document version conflicts.
- Lease acquisition, interruption, release, and deadlock avoidance.
- Text edit ordering and hashes.
- Atomic-save failure handling.
- Semantic ID stability.
- Stale UI revision rejection.
- MCP schema validation.
- Agent event parsing and correlation.
- Terminal backpressure.
- Watcher coalescing and self-save suppression.
- Secret redaction.

### 24.2 Integration tests

- Java commands and generated TypeScript bindings.
- Monaco edit transaction acknowledgement.
- Multi-file workspace edit.
- MCP discovery, authentication, and calls.
- Codex protocol using deterministic protocol fixtures.
- Claude structured stream parsing using recorded fixtures.
- Real filesystem watcher.
- Real PTY open, write, resize, and exit.
- Real Git status and diff.
- LSP initialize, diagnostics, and workspace edits.

Fixtures prove parser behavior only, not live provider integration.

### 24.3 Native JDesk E2E

Run the actual JDesk application with opt-in automation. Assert semantic snapshots, document contents, disk files, process results, and console logs. Screenshots are supplementary only.

Required flows:

1. Launch and list the native window.
2. Open a real temporary workspace.
3. Activate Explorer New File through semantic ID.
4. Enter a filename and create it.
5. Select and open the file.
6. Move the agent pointer to Monaco and focus it.
7. Stream a multi-line edit in every presentation mode.
8. Save and verify exact bytes and hash.
9. Undo and redo the logical edit.
10. Modify the file externally and verify watcher update.
11. Create a dirty-buffer conflict and verify no overwrite.
12. Interrupt typing and verify consistent state.
13. Run a real PTY command and verify output and exit code.
14. Receive a real LSP diagnostic and navigate to it.
15. Verify Git status and diff.
16. Close and restore the workspace.
17. Assert no uncaught WebView error, rejected promise, worker failure, or failed module load.

### 24.4 Live Codex acceptance

Using the user's real authenticated Codex installation:

1. Start Codex through the embedded adapter.
2. Connect it to the real editor MCP server.
3. Give it a task in a real test repository.
4. Require it to create, open, edit through Monaco, save, run tests, and fix a deliberately failing test.
5. Exercise a command approval and a destructive/file approval.
6. Verify provider events, action log, disk changes, Git diff, build output, and tests agree.
7. Interrupt one turn and resume or start another.

If executable or authentication is unavailable, mark the test BLOCKED or FAIL, never PASS.

### 24.5 Live Claude acceptance

Repeat the equivalent workflow through the real authenticated Claude Code installation and structured streaming adapter. Verify that Claude actually called editor MCP tools. A watcher-only change does not satisfy semantic-control acceptance.

If executable or authentication is unavailable, mark the test BLOCKED or FAIL, never PASS.

### 24.6 Cross-platform acceptance

Run native tests on:

- macOS WKWebView;
- Windows WebView2;
- Linux WebKitGTK.

At minimum on each platform:

- launch native window;
- Monaco worker gate;
- semantic activation;
- file create, open, edit, and save;
- copy/paste and keyboard focus;
- real PTY;
- packaged artifact launch;
- console inspection.

CI compilation on another OS is not a substitute for launching its real WebView.

---

## 25. Test evidence and anti-fabrication rules

Every nontrivial verification run creates:

    artifacts/test-runs/<UTC timestamp>/
    ├── manifest.json
    ├── commands.jsonl
    ├── results.json
    ├── app.log
    ├── agent-events.jsonl
    ├── semantic-actions.jsonl
    ├── console.json
    ├── files-before.json
    ├── files-after.json
    ├── git.diff
    ├── test-output.txt
    └── screenshots/

The manifest contains:

- application commit SHA and dirty status;
- JDesk commit or version;
- OS name, version, architecture;
- Java, Gradle, Node build-time, Codex, Claude, Git, and language-server versions;
- native WebView backend and version where available;
- timestamps;
- exact test IDs;
- outcome PASS, FAIL, or BLOCKED;
- evidence paths.

Rules:

1. Capture actual arguments, exit codes, and durations.
2. Verify disk contents and hashes after actions.
3. Verify processes through exit status and observable output.
4. A screenshot alone never proves success.
5. A mocked provider never satisfies live acceptance.
6. A report cannot mark PASS unless evidence was produced by that run.
7. Do not hardcode PASS output in product or test scripts.
8. Missing credentials, OS, WebView, CLI, or source must be BLOCKED.
9. Keep secrets out of artifacts.
10. Produce **VERIFICATION.md** linking each acceptance result to evidence.

---

## 26. Packaging

- Produce native packages through the JDesk packaging flow.
- macOS: application bundle and DMG or PKG as supported.
- Windows: MSI or EXE as supported.
- Linux: DEB or RPM as supported.
- Include the required Java runtime through jlink and jpackage.
- Do not bundle Codex or Claude credentials.
- Detect missing agent CLIs and show setup guidance.
- Separate production packages from E2E test packages.
- Launch every produced package and run a native smoke test before reporting success.

---

## 27. Implementation phases

### Phase 0 — Audit and proofs

- Audit actual JDesk source and tests.
- Prove Monaco workers and CSP on native WebViews.
- Prove a semantic registry and pointer actor.
- Prove one Monaco edit transaction through a typed JDesk round trip.

### Phase 1 — Editor foundation

- Repository skeleton and generated bindings.
- Workspace, document store, tabs, Monaco, Explorer.
- Save, undo/redo, watcher, conflict handling.
- Settings, persistence, recovery journal.

### Phase 2 — Semantic interaction

- Semantic UI Registry.
- Pointer actor and choreography queue.
- Edit leases and Action Coordinator.
- Three presentation modes.
- Deterministic action log.

### Phase 3 — MCP

- Authenticated MCP server.
- Required tools and schemas.
- Capability and approval gates.
- External MCP client test.

### Phase 4 — Agent adapters

- Codex App Server adapter.
- Claude structured-stream adapter.
- Agent UI, approvals, stop and resume.
- Live Codex and Claude acceptance.

### Phase 5 — Developer tooling

- PTY and xterm.js.
- Search.
- Git.
- Prioritized LSP services.

### Phase 6 — Hardening

- Security and performance tests.
- Crash/recovery tests.
- Native cross-platform E2E.
- Package and launch real artifacts.
- Final documentation and verification evidence.

Do not build later phases on a failed phase-zero gate.

---

## 28. Required documentation

- **README.md:** install, build, run, agent setup, limitations.
- **docs/ARCHITECTURE.md:** modules, trust boundaries, threads, protocols, state ownership.
- **docs/JDESK_AUDIT.md:** verified capabilities and source evidence.
- **docs/SEMANTIC_UI.md:** IDs, snapshots, actions, revisions.
- **docs/MCP_TOOLS.md:** tools, schemas, errors, examples.
- **docs/AGENT_ADAPTERS.md:** Codex and Claude lifecycle mapping.
- **docs/SECURITY.md:** capabilities, paths, tokens, approvals, threat model.
- **docs/TESTING.md:** test tiers and commands.
- **docs/PERFORMANCE.md:** methodology and results.
- **VERIFICATION.md:** actual results and evidence paths.
- **CHANGELOG.md.**

Documentation must describe implemented behavior only.

---

## 29. Definition of Done

The project is complete only when all applicable items are true:

- [ ] Actual JDesk source or dependency version was audited and recorded.
- [ ] Monaco worker gate passed on native WebViews, or unavailable platforms are explicitly BLOCKED and cross-platform completion is not claimed.
- [ ] Normal editor workflows function without an agent.
- [ ] Semantic actions use stable IDs and never agent-provided coordinates.
- [ ] Pointer overlay activates real application commands.
- [ ] All presentation modes produce byte-identical final content.
- [ ] Version conflicts and edit leases prevent silent overwrites.
- [ ] Watcher mode works and is distinguished from semantic MCP control.
- [ ] MCP is authenticated, capability-scoped, and path-safe.
- [ ] Embedded Codex passed a live authenticated workflow.
- [ ] Embedded Claude passed a live authenticated workflow.
- [ ] Real PTY, Git, and real LSP acceptance passed.
- [ ] Required native E2E flows passed on available real platforms.
- [ ] Production packages were built and launched.
- [ ] Acceptance runs contain no unhandled WebView console errors.
- [ ] Unit, integration, E2E, live-agent, security, and performance results are recorded honestly.
- [ ] Every PASS in VERIFICATION.md points to real evidence.
- [ ] No critical TODO, disabled test, placeholder, fake backend, or fabricated success remains.
- [ ] A clean checkout builds with documented commands.

If a live dependency is unavailable, the final report must state exactly what is complete and what is BLOCKED. It must never reinterpret blocked or unexecuted checks as success.

---

## 30. Primary references

- JDesk documentation: https://jdesk.dev/docs
- JDesk Automation: https://jdesk.dev/docs/automation-e2e
- JDesk IPC: https://jdesk.dev/docs/protocol
- JDesk binary streaming: https://jdesk.dev/docs/streaming-binary-data
- Monaco API: https://microsoft.github.io/monaco-editor/typedoc/
- MCP specification: https://modelcontextprotocol.io/specification/2025-11-25
- Codex App Server: https://developers.openai.com/codex/app-server
- Codex MCP: https://developers.openai.com/codex/mcp
- Claude Code MCP: https://docs.anthropic.com/en/docs/claude-code/mcp
- Language Server Protocol: https://microsoft.github.io/language-server-protocol/
- xterm.js: https://github.com/xtermjs/xterm.js
- pty4j: https://github.com/JetBrains/pty4j
- Eclipse LSP4J: https://github.com/eclipse-lsp4j/lsp4j