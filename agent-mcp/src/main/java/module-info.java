/**
 * Authenticated loopback MCP server (spec §13). Exposes editor operations to external and embedded
 * agents as Model Context Protocol tools over Streamable HTTP (JSON-RPC 2.0), so an agent creates,
 * opens, edits, saves, and inspects through the real editor rather than by guessing at files.
 */
module dev.jdesk.editor.mcp {
    requires transitive dev.jdesk.editor.api;
    requires dev.jdesk.editor.core;
    requires com.fasterxml.jackson.databind;
    requires jdk.httpserver;

    exports dev.jdesk.editor.mcp;
}
