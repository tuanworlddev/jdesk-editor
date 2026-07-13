package dev.jdesk.editor.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.jdesk.editor.api.EditorErrorCode;
import dev.jdesk.editor.api.EditorException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Authenticated loopback MCP server (spec §13). Streamable HTTP with JSON-RPC 2.0 on a single
 * {@code POST /mcp} endpoint (2025-11-25). Binds to 127.0.0.1 on an ephemeral port, issues a
 * high-entropy bearer token per run, and writes an owner-only discovery file. Non-loopback origins
 * and unauthenticated requests are rejected. No JDesk evaluate surface is exposed.
 */
public final class McpServer implements AutoCloseable {

    public static final String PROTOCOL_VERSION = "2025-11-25";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EditorBridge bridge;
    private final ToolCatalog catalog = new ToolCatalog();
    private final String token;
    private final Path discoveryFile;

    private HttpServer http;
    private int port;

    public McpServer(EditorBridge bridge, Path discoveryFile) {
        this.bridge = bridge;
        this.discoveryFile = discoveryFile;
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        this.token = HexFormat.of().formatHex(raw);
    }

    /** Starts the server and writes the discovery file. Returns the bound port. */
    public synchronized int start() {
        try {
            http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            http.createContext("/mcp", this::handle);
            http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            http.start();
            this.port = http.getAddress().getPort();
            writeDiscoveryFile();
            return port;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to start MCP server", e);
        }
    }

    public int port() {
        return port;
    }

    public String token() {
        return token;
    }

    public String url() {
        return "http://127.0.0.1:" + port + "/mcp";
    }

    @Override
    public synchronized void close() {
        if (http != null) {
            http.stop(0);
            http = null;
        }
        try {
            Files.deleteIfExists(discoveryFile);
        } catch (IOException ignored) {
            // best effort
        }
    }

    // ---- HTTP handling ----

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"method not allowed\"}");
                return;
            }
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !origin.startsWith("http://127.0.0.1") && !origin.startsWith("http://localhost")) {
                respond(exchange, 403, "{\"error\":\"forbidden origin\"}");
                return;
            }
            if (!authorized(exchange)) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer");
                respond(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            JsonNode request = JSON.readTree(body);
            ObjectNode response = dispatch(request);
            if (response == null) {
                // A notification (no id) gets a 202 with no body.
                respond(exchange, 202, "");
            } else {
                exchange.getResponseHeaders().add("MCP-Protocol-Version", PROTOCOL_VERSION);
                respond(exchange, 200, JSON.writeValueAsString(response));
            }
        } catch (Exception e) {
            respond(exchange, 200, jsonRpcError(null, -32603, "Internal error: " + e.getMessage()));
        }
    }

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        String presented = header.substring("Bearer ".length());
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    private ObjectNode dispatch(JsonNode request) {
        String method = request.path("method").asText();
        JsonNode id = request.get("id");
        boolean isNotification = id == null;

        switch (method) {
            case "initialize" -> {
                ObjectNode result = JSON.createObjectNode();
                // Echo the client's requested protocol version when present (negotiation): our tool
                // surface is stable across revisions, so we accept the client's version.
                String requested = request.path("params").path("protocolVersion").asText("");
                result.put("protocolVersion", requested.isBlank() ? PROTOCOL_VERSION : requested);
                result.putObject("capabilities").putObject("tools");
                ObjectNode serverInfo = result.putObject("serverInfo");
                serverInfo.put("name", "jdesk-editor");
                serverInfo.put("version", "0.1.0");
                return jsonRpcResult(id, result);
            }
            case "notifications/initialized" -> {
                return null; // notification, no response
            }
            case "ping" -> {
                return jsonRpcResult(id, JSON.createObjectNode());
            }
            case "tools/list" -> {
                ObjectNode result = JSON.createObjectNode();
                ArrayNode toolsOut = result.putArray("tools");
                for (ToolCatalog.Tool tool : catalog.tools()) {
                    ObjectNode t = toolsOut.addObject();
                    t.put("name", tool.name());
                    t.put("title", tool.title());
                    t.put("description", tool.description());
                    t.set("inputSchema", tool.inputSchema());
                }
                return jsonRpcResult(id, result);
            }
            case "tools/call" -> {
                return isNotification ? null : callTool(id, request.path("params"));
            }
            default -> {
                return isNotification ? null : jsonRpcErrorNode(id, -32601, "Method not found: " + method);
            }
        }
    }

    private ObjectNode callTool(JsonNode id, JsonNode params) {
        String name = params.path("name").asText();
        JsonNode arguments = params.path("arguments");
        ObjectNode result = JSON.createObjectNode();
        try {
            ToolCatalog.Tool tool = catalog.resolve(name);
            JsonNode structured = tool.handler().call(bridge, arguments);
            ArrayNode content = result.putArray("content");
            ObjectNode text = content.addObject();
            text.put("type", "text");
            text.put("text", JSON.writeValueAsString(structured));
            result.set("structuredContent", structured);
            result.put("isError", false);
        } catch (McpToolException e) {
            fillError(result, e.code(), e.getMessage());
        } catch (EditorException e) {
            fillError(result, e.code(), e.getMessage());
        } catch (Exception e) {
            fillError(result, EditorErrorCode.INTERNAL_ERROR, e.getMessage());
        }
        return jsonRpcResult(id, result);
    }

    private void fillError(ObjectNode result, EditorErrorCode code, String message) {
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", "[" + code + "] " + message);
        ObjectNode structured = result.putObject("structuredContent");
        structured.put("code", code.name());
        structured.put("message", message);
        result.put("isError", true);
    }

    // ---- JSON-RPC helpers ----

    private ObjectNode jsonRpcResult(JsonNode id, JsonNode result) {
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.set("id", id);
        envelope.set("result", result);
        return envelope;
    }

    private String jsonRpcError(JsonNode id, int code, String message) {
        try {
            return JSON.writeValueAsString(jsonRpcErrorNode(id, code, message));
        } catch (IOException e) {
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"serialize\"}}";
        }
    }

    private ObjectNode jsonRpcErrorNode(JsonNode id, int code, String message) {
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.set("id", id);
        ObjectNode error = envelope.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return envelope;
    }

    private void writeDiscoveryFile() throws IOException {
        ObjectNode descriptor = JSON.createObjectNode();
        descriptor.put("url", url());
        descriptor.put("token", token);
        descriptor.put("pid", ProcessHandle.current().pid());
        descriptor.put("protocolVersion", PROTOCOL_VERSION);
        Files.createDirectories(discoveryFile.getParent());
        Files.writeString(discoveryFile, JSON.writeValueAsString(descriptor), StandardCharsets.UTF_8);
        try {
            Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(discoveryFile, ownerOnly);
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
