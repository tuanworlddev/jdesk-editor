/**
 * JDesk Editor core: workspace/document model, path canonicalization, edit leases and
 * transactions, atomic save, filesystem watching, search, settings, and the action log. Pure
 * domain logic — no WebView or agent dependency — so it is exercised headlessly by unit tests.
 */
module dev.jdesk.editor.core {
    requires transitive dev.jdesk.editor.api;

    exports dev.jdesk.editor.core.fs;
    exports dev.jdesk.editor.core.doc;
    exports dev.jdesk.editor.core.search;
}
