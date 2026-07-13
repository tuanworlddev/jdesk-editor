/**
 * Terminal service (spec §17): batches and flow-controls PTY output so a high-volume producer
 * (10 MiB of {@code cat}) never overflows the framework's bounded event queue or grows memory
 * without bound. The PTY itself comes from the JDesk runtime; this module owns the backpressure.
 */
module dev.jdesk.editor.terminal {
    requires transitive dev.jdesk.editor.api;

    exports dev.jdesk.editor.terminal;
}
