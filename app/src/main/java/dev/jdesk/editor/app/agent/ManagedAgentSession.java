package dev.jdesk.editor.app.agent;

import java.util.List;

/**
 * An embedded, app-managed agent session (spec §15). The running editor spawns the user's
 * authenticated agent CLI (Claude Code or Codex) as a subprocess, pointed at the editor's own
 * loopback MCP server, so the agent's edits go through the editor and appear live. Both providers
 * expose the same start/observe/interrupt lifecycle behind this interface.
 */
public interface ManagedAgentSession {

    /** A point-in-time view of the session for the UI to poll. */
    record Status(boolean done, boolean success, List<String> toolCalls, String result,
            String providerSessionId) {}

    /** Spawns the agent with the given prompt, pointed at the editor's MCP tools. */
    void start(String prompt);

    /** The current session state (safe to call repeatedly from the poll loop). */
    Status status();

    /** Terminates the underlying process. */
    void interrupt();

    /** The editor's stable session id. */
    String sessionId();
}
