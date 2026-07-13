/**
 * JDesk Editor application: wires the editor core to the JDesk runtime through typed
 * {@code @DesktopCommand} facades, serves the React/Monaco frontend over {@code jdesk://app}, and
 * owns the outbound event dispatcher. The command facades are the only place editor operations
 * cross into the WebView.
 */
module dev.jdesk.editor.app {
    requires dev.jdesk.api;
    requires dev.jdesk.runtime;
    requires transitive dev.jdesk.editor.core;
    requires static com.fasterxml.jackson.databind;

    // The runtime binds command request/response records reflectively via Jackson.
    opens dev.jdesk.editor.app.ipc to com.fasterxml.jackson.databind, dev.jdesk.runtime;
}
