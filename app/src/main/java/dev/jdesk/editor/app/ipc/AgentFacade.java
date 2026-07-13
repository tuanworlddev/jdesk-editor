package dev.jdesk.editor.app.ipc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.RequiresCapability;
import dev.jdesk.editor.api.EditorErrorCode;
import dev.jdesk.editor.api.EditorException;
import dev.jdesk.editor.app.agent.ManagedAgentSession;
import dev.jdesk.editor.app.agent.ManagedClaudeSession;
import dev.jdesk.editor.app.agent.ManagedCodexSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Typed IPC facade for embedded agent sessions (spec §15). The running editor spawns and manages
 * the user's authenticated agent CLI — Claude Code or Codex — pointed at the editor's own MCP
 * server, so the agent's edits flow through the editor and appear live. This is the embedded
 * lifecycle — start, observe, interrupt — behind a typed command surface the UI drives.
 */
public final class AgentFacade {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Supplier<Path> mcpDir;
    private final Map<String, ManagedAgentSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();

    /** @param mcpDir directory holding the MCP {@code mcp-config.json} (Claude) and {@code discovery.json} (Codex). */
    public AgentFacade(Supplier<Path> mcpDir) {
        this.mcpDir = mcpDir;
    }

    @DesktopCommand("agent.start")
    @RequiresCapability("editor:core")
    public CompletionStage<AgentDtos.AgentSession> start(
            AgentDtos.StartRequest request, InvocationContext context) {
        Path dir = mcpDir.get();
        if (dir == null || !Files.exists(dir)) {
            throw new EditorException(EditorErrorCode.AGENT_NOT_AVAILABLE,
                    "MCP server is not running; start the app with agent support enabled");
        }
        String provider = request.provider() == null || request.provider().isBlank()
                ? "claude" : request.provider().toLowerCase();
        ManagedAgentSession session = "codex".equals(provider)
                ? newCodex(dir) : newClaude(dir);
        sessions.put(session.sessionId(), session);
        session.start(request.prompt());
        return CompletableFuture.completedFuture(
                new AgentDtos.AgentSession(session.sessionId(), provider));
    }

    private ManagedAgentSession newClaude(Path dir) {
        Path config = dir.resolve("mcp-config.json");
        if (!Files.exists(config)) {
            throw new EditorException(EditorErrorCode.AGENT_NOT_AVAILABLE, "MCP config not found");
        }
        return new ManagedClaudeSession("claude-" + counter.incrementAndGet(), config,
                System.getProperty("jdesk.editor.claude", "claude"));
    }

    private ManagedAgentSession newCodex(Path dir) {
        Path discovery = dir.resolve("discovery.json");
        if (!Files.exists(discovery)) {
            throw new EditorException(EditorErrorCode.AGENT_NOT_AVAILABLE, "MCP discovery not found");
        }
        String url;
        String token;
        try {
            JsonNode d = JSON.readTree(Files.readString(discovery));
            url = d.path("url").asText();
            token = d.path("token").asText();
        } catch (Exception e) {
            throw new EditorException(EditorErrorCode.AGENT_NOT_AVAILABLE,
                    "could not read MCP discovery: " + e.getMessage());
        }
        return new ManagedCodexSession("codex-" + counter.incrementAndGet(), url, token,
                System.getProperty("jdesk.editor.codex", "codex"));
    }

    @DesktopCommand("agent.status")
    @RequiresCapability("editor:core")
    public CompletionStage<AgentDtos.AgentStatus> status(
            AgentDtos.StatusRequest request, InvocationContext context) {
        ManagedAgentSession session = sessions.get(request.sessionId());
        if (session == null) {
            throw new EditorException(EditorErrorCode.AGENT_NOT_AVAILABLE, "No such agent session");
        }
        ManagedAgentSession.Status status = session.status();
        return CompletableFuture.completedFuture(new AgentDtos.AgentStatus(
                request.sessionId(), status.done(), status.success(),
                status.toolCalls(), status.result()));
    }

    @DesktopCommand("agent.interrupt")
    @RequiresCapability("editor:core")
    public CompletionStage<AgentDtos.AgentStatus> interrupt(
            AgentDtos.StatusRequest request, InvocationContext context) {
        ManagedAgentSession session = sessions.get(request.sessionId());
        if (session != null) {
            session.interrupt();
        }
        return status(request, context);
    }
}
