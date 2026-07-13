/**
 * Phase-0 Monaco worker gate application. A real JDesk desktop app that serves Monaco from the
 * production {@code jdesk://app} custom scheme and proves — mechanically — that Monaco's web
 * workers, CSP, and semantic/edit primitives function on the native WebView.
 */
module dev.jdesk.editor.gate {
    requires dev.jdesk.api;
    requires dev.jdesk.runtime;
    requires dev.jdesk.webview.spi;
    requires com.fasterxml.jackson.databind;
    requires java.net.http;

    uses dev.jdesk.webview.spi.PlatformProvider;
}
