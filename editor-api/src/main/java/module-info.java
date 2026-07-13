/**
 * JDesk Editor API: wire DTO records, port interfaces, value types, and error codes shared across
 * the editor core, the app command facades, and the MCP tool catalog. Pure JDK — no framework
 * dependency — so it sits at the bottom of the module graph with no cycles.
 */
module dev.jdesk.editor.api {
    exports dev.jdesk.editor.api;
    exports dev.jdesk.editor.api.wire;
}
