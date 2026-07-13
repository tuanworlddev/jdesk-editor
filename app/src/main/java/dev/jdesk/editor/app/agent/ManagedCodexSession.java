package dev.jdesk.editor.app.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An embedded, app-managed Codex session (spec §15). The running editor spawns the user's
 * authenticated {@code codex exec --json} CLI as a subprocess, pointed at the editor's own loopback
 * MCP server (streamable HTTP + bearer token via an env var, never argv), so the agent's edits go
 * through the editor and appear live. Codex's JSONL event stream is parsed off stdout; the session
 * tracks tool activity and the final agent message. This is the Codex twin of
 * {@link ManagedClaudeSession} — same lifecycle, provider-specific protocol.
 */
public final class ManagedCodexSession implements ManagedAgentSession {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN_ENV = "JDESK_MCP_TOKEN";

    private final String sessionId;
    private final String mcpUrl;
    private final String mcpToken;
    private final String codexExecutable;

    private volatile Process process;
    private final AtomicBoolean done = new AtomicBoolean();
    private final AtomicReference<String> threadId = new AtomicReference<>("");
    private final AtomicReference<String> resultText = new AtomicReference<>("");
    private final AtomicBoolean success = new AtomicBoolean();
    private final List<String> toolCalls = new CopyOnWriteArrayList<>();

    public ManagedCodexSession(String sessionId, String mcpUrl, String mcpToken, String codexExecutable) {
        this.sessionId = sessionId;
        this.mcpUrl = mcpUrl;
        this.mcpToken = mcpToken;
        this.codexExecutable = codexExecutable == null ? "codex" : codexExecutable;
    }

    /**
     * Spawns Codex with the editor's MCP server wired in. The editor's tools are made available as
     * {@code mcp_servers.jdesk_editor}; a read-only shell sandbox nudges file edits through those
     * tools rather than direct disk writes, so they flow through the editor.
     */
    @Override
    public void start(String prompt) {
        String instructed = "Use the jdesk_editor MCP tools (editor_open, editor_apply_workspace_edit, "
                + "editor_save, file_create) to read and edit files so your changes appear in the "
                + "running editor. Task: " + prompt;
        List<String> argv = new ArrayList<>(List.of(
                codexExecutable, "exec", "--json",
                "--sandbox", "read-only",
                "-c", "mcp_servers.jdesk_editor.url=\"" + mcpUrl + "\"",
                "-c", "mcp_servers.jdesk_editor.bearer_token_env_var=\"" + TOKEN_ENV + "\"",
                instructed));
        try {
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.environment().put(TOKEN_ENV, mcpToken);
            builder.redirectErrorStream(false);
            process = builder.start();
            // We pass the prompt as an argument, so close stdin to stop Codex waiting on it.
            process.getOutputStream().close();
            Thread reader = new Thread(this::readStream, "codex-" + sessionId);
            reader.setDaemon(true);
            reader.start();
        } catch (IOException e) {
            resultText.set("failed to start codex: " + e.getMessage());
            done.set(true);
        }
    }

    private void readStream() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    parseEvent(line);
                }
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
            switch (msg.path("type").asText()) {
                case "thread.started" -> threadId.set(msg.path("thread_id").asText(""));
                case "item.completed", "item.started" -> {
                    JsonNode item = msg.path("item");
                    switch (item.path("type").asText()) {
                        case "mcp_tool_call" -> toolCalls.add(
                                item.path("tool").asText(item.path("name").asText("mcp_tool")));
                        case "command_execution" -> toolCalls.add(
                                "$ " + item.path("command").asText("command"));
                        case "file_change", "patch" -> toolCalls.add("file_change");
                        case "agent_message" -> resultText.set(item.path("text").asText(""));
                        default -> { /* reasoning, etc. */ }
                    }
                }
                case "turn.completed" -> success.set(true);
                case "error" -> {
                    resultText.set(msg.path("message").asText("Codex error"));
                    done.set(true);
                }
                default -> { /* turn.started, token counts, etc. */ }
            }
        } catch (IOException e) {
            // non-JSON line (human-readable log)
        }
    }

    @Override
    public Status status() {
        return new Status(done.get(), success.get(), List.copyOf(toolCalls),
                resultText.get(), threadId.get());
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public void interrupt() {
        Process p = process;
        if (p != null) {
            p.destroy();
        }
    }
}
