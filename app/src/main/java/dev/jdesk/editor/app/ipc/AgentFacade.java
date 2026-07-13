package dev.jdesk.editor.app.ipc;

import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.RequiresCapability;
import dev.jdesk.editor.api.EditorErrorCode;
import dev.jdesk.editor.api.EditorException;
import dev.jdesk.editor.app.agent.ManagedClaudeSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Typed IPC facade for embedded agent sessions (spec §15). The running editor spawns and manages
 * the user's authenticated Claude Code CLI, pointed at the editor's own MCP server, so the agent's
 * edits flow through the editor and appear live. This is the embedded lifecycle — start, observe,
 * interrupt — behind a typed command surface the UI drives.
 */
public final class AgentFacade {

    private final Supplier<Path> mcpConfigPath;
    private final Map<String, ManagedClaudeSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();

    public AgentFacade(Supplier<Path> mcpConfigPath) {
        this.mcpConfigPath = mcpConfigPath;
    }

    @DesktopCommand("agent.startClaude")
    @RequiresCapability("editor:core")
    public CompletionStage<AgentDtos.AgentSession> startClaude(
            AgentDtos.StartRequest request, InvocationContext context) {
        Path config = mcpConfigPath.get();
        if (config == null || !Files.exists(config)) {
            throw new EditorException(EditorErrorCode.AGENT_NOT_AVAILABLE,
                    "MCP server is not running; start the app with agent support enabled");
        }
        String id = "claude-" + counter.incrementAndGet();
        ManagedClaudeSession session = new ManagedClaudeSession(id, config,
                System.getProperty("jdesk.editor.claude", "claude"));
        sessions.put(id, session);
        session.start(request.prompt());
        return CompletableFuture.completedFuture(new AgentDtos.AgentSession(id, "claude"));
    }

    @DesktopCommand("agent.status")
    @RequiresCapability("editor:core")
    public CompletionStage<AgentDtos.AgentStatus> status(
            AgentDtos.StatusRequest request, InvocationContext context) {
        ManagedClaudeSession session = sessions.get(request.sessionId());
        if (session == null) {
            throw new EditorException(EditorErrorCode.AGENT_NOT_AVAILABLE, "No such agent session");
        }
        ManagedClaudeSession.Status status = session.status();
        return CompletableFuture.completedFuture(new AgentDtos.AgentStatus(
                request.sessionId(), status.done(), status.success(),
                status.toolCalls(), status.result()));
    }

    @DesktopCommand("agent.interrupt")
    @RequiresCapability("editor:core")
    public CompletionStage<AgentDtos.AgentStatus> interrupt(
            AgentDtos.StatusRequest request, InvocationContext context) {
        ManagedClaudeSession session = sessions.get(request.sessionId());
        if (session != null) {
            session.interrupt();
        }
        return status(request, context);
    }
}
