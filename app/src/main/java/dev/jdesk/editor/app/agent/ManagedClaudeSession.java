package dev.jdesk.editor.app.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An embedded, app-managed Claude Code session (spec §15). The running editor spawns the user's
 * authenticated {@code claude} CLI as a subprocess, pointed at the editor's own loopback MCP server,
 * so the agent's edits go through the editor and appear live. Streaming events are parsed off the
 * process's stdout; the session tracks tool calls and completion. This is the embedded lifecycle —
 * the editor owns the process — as distinct from an external client connecting to MCP.
 */
public final class ManagedClaudeSession {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String sessionId;
    private final Path mcpConfig;
    private final String claudeExecutable;

    private volatile Process process;
    private final AtomicBoolean done = new AtomicBoolean();
    private final AtomicReference<String> claudeSessionId = new AtomicReference<>("");
    private final AtomicReference<String> resultText = new AtomicReference<>("");
    private final AtomicBoolean success = new AtomicBoolean();
    private final List<String> toolCalls = new CopyOnWriteArrayList<>();
    private final List<String> rawEventTypes = new CopyOnWriteArrayList<>();

    public ManagedClaudeSession(String sessionId, Path mcpConfig, String claudeExecutable) {
        this.sessionId = sessionId;
        this.mcpConfig = mcpConfig;
        this.claudeExecutable = claudeExecutable == null ? "claude" : claudeExecutable;
    }

    public record Status(boolean done, boolean success, List<String> toolCalls, String result,
            String claudeSessionId) {}

    /** Spawns the agent with the given prompt, restricted to the editor's MCP tools. */
    public void start(String prompt) {
        List<String> argv = List.of(
                claudeExecutable, "-p", prompt,
                "--mcp-config", mcpConfig.toString(),
                "--allowedTools",
                "mcp__jdesk_editor__file_create",
                "mcp__jdesk_editor__editor_open",
                "mcp__jdesk_editor__editor_apply_workspace_edit",
                "mcp__jdesk_editor__editor_save",
                "mcp__jdesk_editor__workspace_get_state",
                "--output-format", "stream-json", "--verbose", "--max-turns", "12");
        try {
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.redirectErrorStream(false);
            process = builder.start();
            Thread reader = new Thread(this::readStream, "claude-" + sessionId);
            reader.setDaemon(true);
            reader.start();
        } catch (IOException e) {
            resultText.set("failed to start claude: " + e.getMessage());
            done.set(true);
        }
    }

    private void readStream() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                parseEvent(line);
            }
        } catch (IOException e) {
            // stream closed
        } finally {
            done.set(true);
        }
    }

    private void parseEvent(String line) {
        try {
            JsonNode msg = JSON.readTree(line);
            String type = msg.path("type").asText();
            rawEventTypes.add(type);
            switch (type) {
                case "system" -> {
                    if ("init".equals(msg.path("subtype").asText())) {
                        claudeSessionId.set(msg.path("session_id").asText(""));
                    }
                }
                case "assistant" -> {
                    for (JsonNode block : msg.path("message").path("content")) {
                        if ("tool_use".equals(block.path("type").asText())) {
                            toolCalls.add(block.path("name").asText());
                        }
                    }
                }
                case "result" -> {
                    success.set("success".equals(msg.path("subtype").asText())
                            && !msg.path("is_error").asBoolean());
                    resultText.set(msg.path("result").asText(""));
                }
                default -> { /* deltas, etc. */ }
            }
        } catch (IOException e) {
            // non-JSON line
        }
    }

    public Status status() {
        return new Status(done.get(), success.get(), List.copyOf(toolCalls),
                resultText.get(), claudeSessionId.get());
    }

    public String sessionId() {
        return sessionId;
    }

    public void interrupt() {
        Process p = process;
        if (p != null) {
            p.destroy();
        }
    }
}
