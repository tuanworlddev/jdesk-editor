package dev.jdesk.editor.mcp;

import dev.jdesk.editor.api.wire.TextEditDto;

import java.util.List;
import java.util.Map;

/**
 * The editor operations MCP tools invoke, implemented by the running app (backed by the shared
 * {@code EditorSession} and the window event emitter, so agent edits appear in the live UI). Kept
 * as an interface so the server is unit- and integration-testable against an in-memory editor core
 * without a WebView. Every mutating call returns the spec §13.2 operation envelope.
 */
public interface EditorBridge {

    record WorkspaceInfo(boolean open, String rootName, String rootPath) {}

    record EntryInfo(String name, String relPath, boolean directory, boolean hasChildren) {}

    record DocumentInfo(String uri, String relPath, long version, String contentHash,
            String content) {}

    record DiagnosticInfo(String relPath, int line, int column, String severity, String message,
            String code) {}

    /** Spec §13.2 mutation envelope. */
    record OperationResult(String operationId, String status, Map<String, Long> documentVersions,
            String summary) {}

    WorkspaceInfo workspace();

    List<EntryInfo> list(String relPath);

    List<EntryInfo> search(String query, int maxResults);

    OperationResult createFile(String relPath);

    OperationResult renameFile(String fromRelPath, String toRelPath);

    OperationResult deleteFile(String relPath);

    DocumentInfo open(String relPath);

    OperationResult applyWorkspaceEdit(String relPath, List<TextEditDto> edits, String agentId);

    OperationResult save(String relPath);

    List<DiagnosticInfo> diagnostics(String relPath);

    // ---- terminal (spec §17) — only the running app supports PTYs; headless bridges opt out ----

    record TerminalRead(String terminalId, String output, boolean alive, Integer exitCode) {}

    default String openTerminal(String command, int cols, int rows) {
        throw new McpToolException(dev.jdesk.editor.api.EditorErrorCode.AGENT_NOT_AVAILABLE,
                "Terminals require the running editor");
    }

    default void writeTerminal(String terminalId, String data) {
        throw new McpToolException(dev.jdesk.editor.api.EditorErrorCode.AGENT_NOT_AVAILABLE,
                "Terminals require the running editor");
    }

    default TerminalRead readTerminal(String terminalId) {
        throw new McpToolException(dev.jdesk.editor.api.EditorErrorCode.AGENT_NOT_AVAILABLE,
                "Terminals require the running editor");
    }

    default void closeTerminal(String terminalId) {
        throw new McpToolException(dev.jdesk.editor.api.EditorErrorCode.AGENT_NOT_AVAILABLE,
                "Terminals require the running editor");
    }
}
