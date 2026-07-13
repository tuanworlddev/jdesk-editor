package dev.jdesk.editor.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jdesk.editor.api.EditorErrorCode;
import dev.jdesk.editor.api.wire.TextEditDto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The editor's MCP tool set (a focused, real subset of spec §13.2). Tool names use underscores
 * because Anthropic's tool-name grammar rejects dots; the dotted spec aliases are also accepted on
 * {@code tools/call}. Each tool binds its JSON arguments and calls the {@link EditorBridge}, so the
 * same operations a human performs are what an agent drives.
 */
public final class ToolCatalog {

    @FunctionalInterface
    public interface Handler {
        JsonNode call(EditorBridge bridge, JsonNode args) throws McpToolException;
    }

    public record Tool(String name, String title, String description, ObjectNode inputSchema,
            boolean destructive, Handler handler) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<Tool> tools = new ArrayList<>();
    private final Map<String, Tool> byName = new LinkedHashMap<>();

    public ToolCatalog() {
        register("workspace_get_state", "workspace.get_state",
                "Get the active workspace root and top-level entries.",
                schema(), false,
                (bridge, args) -> {
                    EditorBridge.WorkspaceInfo ws = bridge.workspace();
                    ObjectNode out = JSON.createObjectNode();
                    out.put("open", ws.open());
                    out.put("rootName", ws.rootName());
                    out.put("rootPath", ws.rootPath());
                    return out;
                });

        register("workspace_list", "workspace.list",
                "List the immediate children of a workspace-relative directory (empty = root).",
                schema(prop("relPath", "string", "Workspace-relative directory, empty for root")), false,
                (bridge, args) -> entries(bridge.list(text(args, "relPath", ""))));

        register("workspace_search", "workspace.search",
                "Find files whose path contains the query substring.",
                schema(prop("query", "string", "Substring to match in file paths"),
                        prop("maxResults", "integer", "Maximum results (default 50)")), false,
                (bridge, args) -> entries(bridge.search(required(args, "query"),
                        args.has("maxResults") ? args.get("maxResults").asInt() : 50)));

        register("file_create", "file.create",
                "Create a new empty file at a workspace-relative path and open it.",
                schema(prop("relPath", "string", "Workspace-relative path of the new file")), false,
                (bridge, args) -> envelope(bridge.createFile(required(args, "relPath"))));

        register("editor_open", "editor.open",
                "Open a document and return its uri, version, content hash, and content.",
                schema(prop("relPath", "string", "Workspace-relative path")), false,
                (bridge, args) -> {
                    EditorBridge.DocumentInfo doc = bridge.open(required(args, "relPath"));
                    ObjectNode out = JSON.createObjectNode();
                    out.put("uri", doc.uri());
                    out.put("relPath", doc.relPath());
                    out.put("version", doc.version());
                    out.put("contentHash", doc.contentHash());
                    out.put("content", doc.content());
                    return out;
                });

        register("editor_apply_workspace_edit", "editor.apply_workspace_edit",
                "Apply a batch of text edits to a document through the editor (appears live in the UI).",
                editSchema(), false,
                (bridge, args) -> {
                    String relPath = required(args, "relPath");
                    List<TextEditDto> edits = parseEdits(args.get("edits"));
                    String agentId = text(args, "agentId", "mcp");
                    return envelope(bridge.applyWorkspaceEdit(relPath, edits, agentId));
                });

        register("editor_save", "editor.save",
                "Persist a document to disk atomically and return the new version and disk hash.",
                schema(prop("relPath", "string", "Workspace-relative path")), false,
                (bridge, args) -> envelope(bridge.save(required(args, "relPath"))));

        register("editor_get_diagnostics", "editor.get_diagnostics",
                "Return the current diagnostics for a document.",
                schema(prop("relPath", "string", "Workspace-relative path")), false,
                (bridge, args) -> {
                    ArrayNode out = JSON.createArrayNode();
                    for (EditorBridge.DiagnosticInfo d : bridge.diagnostics(required(args, "relPath"))) {
                        ObjectNode n = out.addObject();
                        n.put("relPath", d.relPath());
                        n.put("line", d.line());
                        n.put("column", d.column());
                        n.put("severity", d.severity());
                        n.put("message", d.message());
                        n.put("code", d.code());
                    }
                    return out;
                });

        register("terminal_open", "terminal.open",
                "Open a terminal (PTY) in the workspace and return its id.",
                schema(prop("command", "string", "Optional shell command; default is the login shell"),
                        prop("cols", "integer", "Columns (default 80)"),
                        prop("rows", "integer", "Rows (default 24)")), false,
                (bridge, args) -> {
                    String id = bridge.openTerminal(text(args, "command", ""),
                            args.has("cols") ? args.get("cols").asInt() : 80,
                            args.has("rows") ? args.get("rows").asInt() : 24);
                    ObjectNode out = JSON.createObjectNode();
                    out.put("terminalId", id);
                    return out;
                });

        register("terminal_write", "terminal.write",
                "Write input to a terminal (include a trailing newline to run a command).",
                schema(prop("terminalId", "string", "Terminal id"),
                        prop("data", "string", "Bytes to write")), false,
                (bridge, args) -> {
                    bridge.writeTerminal(required(args, "terminalId"), text(args, "data", ""));
                    ObjectNode out = JSON.createObjectNode();
                    out.put("ok", true);
                    return out;
                });

        register("terminal_read", "terminal.read",
                "Read (and clear) accumulated terminal output; reports liveness and exit code.",
                schema(prop("terminalId", "string", "Terminal id")), false,
                (bridge, args) -> {
                    EditorBridge.TerminalRead read = bridge.readTerminal(required(args, "terminalId"));
                    ObjectNode out = JSON.createObjectNode();
                    out.put("terminalId", read.terminalId());
                    out.put("output", read.output());
                    out.put("alive", read.alive());
                    if (read.exitCode() != null) {
                        out.put("exitCode", read.exitCode());
                    }
                    return out;
                });

        register("terminal_close", "terminal.close",
                "Close a terminal and terminate its process.",
                schema(prop("terminalId", "string", "Terminal id")), false,
                (bridge, args) -> {
                    bridge.closeTerminal(required(args, "terminalId"));
                    ObjectNode out = JSON.createObjectNode();
                    out.put("ok", true);
                    return out;
                });
    }

    public List<Tool> tools() {
        return List.copyOf(tools);
    }

    /** Resolves a tool by its underscore name or its dotted spec alias. */
    public Tool resolve(String name) {
        Tool tool = byName.get(name);
        if (tool == null) {
            throw new McpToolException(EditorErrorCode.TARGET_NOT_FOUND, "Unknown tool: " + name);
        }
        return tool;
    }

    // ---- registration + schema helpers ----

    private void register(String name, String dottedAlias, String description, ObjectNode schema,
            boolean destructive, Handler handler) {
        Tool tool = new Tool(name, dottedAlias, description, schema, destructive, handler);
        tools.add(tool);
        byName.put(name, tool);
        byName.put(dottedAlias, tool);
    }

    private ArrayNode entries(List<EditorBridge.EntryInfo> list) {
        ArrayNode out = JSON.createArrayNode();
        for (EditorBridge.EntryInfo e : list) {
            ObjectNode n = out.addObject();
            n.put("name", e.name());
            n.put("relPath", e.relPath());
            n.put("directory", e.directory());
            n.put("hasChildren", e.hasChildren());
        }
        return out;
    }

    private ObjectNode envelope(EditorBridge.OperationResult result) {
        ObjectNode out = JSON.createObjectNode();
        out.put("operationId", result.operationId());
        out.put("status", result.status());
        ObjectNode versions = out.putObject("documentVersions");
        result.documentVersions().forEach(versions::put);
        out.put("summary", result.summary());
        return out;
    }

    private static ObjectNode schema(ObjectNode... properties) {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        for (ObjectNode p : properties) {
            props.setAll((ObjectNode) p);
        }
        return schema;
    }

    private static ObjectNode prop(String name, String type, String description) {
        ObjectNode wrapper = JSON.createObjectNode();
        ObjectNode field = wrapper.putObject(name);
        field.put("type", type);
        field.put("description", description);
        return wrapper;
    }

    private static ObjectNode editSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("relPath").put("type", "string").put("description", "Workspace-relative path");
        ObjectNode edits = props.putObject("edits");
        edits.put("type", "array");
        edits.put("description", "TextEdits in 1-based Monaco coordinates");
        ObjectNode item = edits.putObject("items");
        item.put("type", "object");
        ObjectNode ep = item.putObject("properties");
        for (String field : List.of("startLine", "startColumn", "endLine", "endColumn")) {
            ep.putObject(field).put("type", "integer");
        }
        ep.putObject("text").put("type", "string");
        ArrayNode required = schema.putArray("required");
        required.add("relPath");
        required.add("edits");
        return schema;
    }

    private static List<TextEditDto> parseEdits(JsonNode editsNode) {
        if (editsNode == null || !editsNode.isArray()) {
            throw new McpToolException(EditorErrorCode.INVALID_ARGUMENT, "edits must be an array");
        }
        List<TextEditDto> edits = new ArrayList<>();
        for (JsonNode e : editsNode) {
            try {
                edits.add(new TextEditDto(
                        e.get("startLine").asInt(), e.get("startColumn").asInt(),
                        e.get("endLine").asInt(), e.get("endColumn").asInt(),
                        e.get("text").asText()));
            } catch (RuntimeException ex) {
                throw new McpToolException(EditorErrorCode.INVALID_ARGUMENT,
                        "Invalid edit: " + ex.getMessage());
            }
        }
        return edits;
    }

    private static String required(JsonNode args, String field) {
        if (args == null || !args.hasNonNull(field) || args.get(field).asText().isBlank()) {
            throw new McpToolException(EditorErrorCode.INVALID_ARGUMENT, "Missing argument: " + field);
        }
        return args.get(field).asText();
    }

    private static String text(JsonNode args, String field, String fallback) {
        return args != null && args.hasNonNull(field) ? args.get(field).asText() : fallback;
    }
}
