package dev.jdesk.editor.mcp;

import dev.jdesk.editor.api.EditorErrorCode;

/** A tool-call failure carrying a spec §13.3 error code, surfaced as an MCP {@code isError} result. */
public final class McpToolException extends RuntimeException {

    private final EditorErrorCode code;

    public McpToolException(EditorErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public EditorErrorCode code() {
        return code;
    }
}
