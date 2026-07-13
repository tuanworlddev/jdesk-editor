plugins {
    id("jdesk-editor.java-conventions")
}

// Pure-JDK contracts: wire DTO records, ports, value types, error codes. No framework, no Jackson —
// so both the editor core and the (future) MCP schema generator can depend on it without cycles.
