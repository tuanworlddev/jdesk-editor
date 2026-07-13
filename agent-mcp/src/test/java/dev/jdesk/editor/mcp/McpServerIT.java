package dev.jdesk.editor.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jdesk.editor.core.doc.AtomicSaver;
import dev.jdesk.editor.core.doc.DocumentStore;
import dev.jdesk.editor.core.fs.FileTree;
import dev.jdesk.editor.core.fs.PathService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the MCP server end-to-end over real loopback HTTP with a JSON-RPC client — the same
 * transport codex/claude use. Proves authentication, tool discovery, and that an agent can create,
 * edit, and save a file through the editor, with the bytes landing on disk (spec §24.2 MCP item;
 * one of the top-10 risk tests).
 */
class McpServerIT {

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private McpServer server;
    private Path workspace;

    @BeforeEach
    void setUp(@TempDir Path root) throws IOException {
        workspace = Files.createDirectories(root.resolve("ws"));
        Files.writeString(workspace.resolve("existing.txt"), "hello\n");
        PathService paths = new PathService(workspace);
        DocumentStore documents = new DocumentStore(paths, new AtomicSaver(), System::currentTimeMillis);
        CoreEditorBridge bridge = new CoreEditorBridge(paths, new FileTree(paths), documents, uri -> {});
        server = new McpServer(bridge, root.resolve("mcp/discovery.json"));
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void rejectsUnauthenticatedRequest() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(server.url()))
                        .POST(HttpRequest.BodyPublishers.ofString(rpc("1", "tools/list", null)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void initializeAdvertisesToolsCapability() throws Exception {
        JsonNode result = call(rpc("1", "initialize", json.createObjectNode())).get("result");
        assertThat(result.path("protocolVersion").asText()).isEqualTo(McpServer.PROTOCOL_VERSION);
        assertThat(result.path("capabilities").has("tools")).isTrue();
        assertThat(result.path("serverInfo").path("name").asText()).isEqualTo("jdesk-editor");
    }

    @Test
    void listsTheEditorTools() throws Exception {
        JsonNode tools = call(rpc("2", "tools/list", null)).path("result").path("tools");
        assertThat(tools).isNotEmpty();
        var names = new java.util.ArrayList<String>();
        tools.forEach(t -> names.add(t.path("name").asText()));
        assertThat(names).contains("workspace_get_state", "file_create",
                "editor_apply_workspace_edit", "editor_save", "editor_open");
    }

    @Test
    void agentCreatesEditsAndSavesAFileThroughTheEditor() throws Exception {
        // 1. create a new file
        ObjectNode createArgs = json.createObjectNode();
        createArgs.put("relPath", "src/Greeting.txt");
        JsonNode create = callTool("file_create", createArgs);
        assertThat(create.path("isError").asBoolean()).isFalse();
        assertThat(create.path("structuredContent").path("status").asText()).isEqualTo("COMMITTED");

        // 2. apply an edit through the editor
        ObjectNode editArgs = json.createObjectNode();
        editArgs.put("relPath", "src/Greeting.txt");
        var edits = editArgs.putArray("edits");
        ObjectNode edit = edits.addObject();
        edit.put("startLine", 1);
        edit.put("startColumn", 1);
        edit.put("endLine", 1);
        edit.put("endColumn", 1);
        edit.put("text", "Hello from the agent\n");
        JsonNode applied = callTool("editor_apply_workspace_edit", editArgs);
        assertThat(applied.path("isError").asBoolean()).isFalse();

        // 3. save
        ObjectNode saveArgs = json.createObjectNode();
        saveArgs.put("relPath", "src/Greeting.txt");
        JsonNode saved = callTool("editor_save", saveArgs);
        assertThat(saved.path("isError").asBoolean()).isFalse();

        // 4. the bytes are on disk
        assertThat(Files.readString(workspace.resolve("src/Greeting.txt")))
                .isEqualTo("Hello from the agent\n");
    }

    @Test
    void dottedAliasResolvesToTheSameTool() throws Exception {
        ObjectNode args = json.createObjectNode();
        JsonNode result = callTool("workspace.get_state", args);
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("structuredContent").path("open").asBoolean()).isTrue();
    }

    @Test
    void pathTraversalIsRejectedWithBoundaryError() throws Exception {
        ObjectNode args = json.createObjectNode();
        args.put("relPath", "../escape.txt");
        JsonNode result = callTool("file_create", args);
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("structuredContent").path("code").asText())
                .isEqualTo("WORKSPACE_BOUNDARY_VIOLATION");
    }

    @Test
    void unknownToolReturnsError() throws Exception {
        JsonNode result = callTool("editor_nonexistent", json.createObjectNode());
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("structuredContent").path("code").asText()).isEqualTo("TARGET_NOT_FOUND");
    }

    // ---- helpers ----

    private JsonNode callTool(String name, ObjectNode arguments) throws Exception {
        ObjectNode params = json.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments);
        return call(rpc("9", "tools/call", params)).path("result");
    }

    private JsonNode call(String requestBody) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(server.url()))
                        .header("Authorization", "Bearer " + server.token())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private String rpc(String id, String method, JsonNode params) {
        ObjectNode req = json.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }
        try {
            return json.writeValueAsString(req);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
