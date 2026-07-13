package dev.jdesk.editor.api;

/**
 * Editor and MCP error taxonomy (spec §13.3). These codes cross the IPC and MCP boundaries and
 * must be actionable without leaking secrets or unrelated absolute paths.
 */
public enum EditorErrorCode {
    INVALID_ARGUMENT,
    CAPABILITY_DENIED,
    APPROVAL_REQUIRED,
    WORKSPACE_BOUNDARY_VIOLATION,
    STALE_UI_STATE,
    DOCUMENT_VERSION_CONFLICT,
    EDIT_LEASE_CONFLICT,
    TARGET_NOT_FOUND,
    TARGET_NOT_ACTIONABLE,
    AGENT_NOT_AVAILABLE,
    PROCESS_FAILED,
    TIMEOUT,
    CANCELLED,
    INTERNAL_ERROR
}
