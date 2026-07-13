package dev.jdesk.editor.lsp;

import org.eclipse.lsp4j.Diagnostic;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Collects {@code textDocument/publishDiagnostics} per document URI, keeping only the latest set
 * for each (LSP diagnostics are a full replacement, not a delta). A listener is notified on every
 * update so the app can push Monaco markers.
 */
public final class DiagnosticsSink {

    private final Map<String, List<Diagnostic>> byUri = new ConcurrentHashMap<>();
    private volatile BiConsumer<String, List<Diagnostic>> listener = (uri, diags) -> {};

    public void onUpdate(BiConsumer<String, List<Diagnostic>> listener) {
        this.listener = listener;
    }

    void publish(String uri, List<Diagnostic> diagnostics) {
        byUri.put(uri, List.copyOf(diagnostics));
        listener.accept(uri, diagnostics);
    }

    public List<Diagnostic> forUri(String uri) {
        return byUri.getOrDefault(uri, List.of());
    }
}
