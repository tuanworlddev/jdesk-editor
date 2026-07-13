package dev.jdesk.editor.lsp;

import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;

import java.util.concurrent.CompletableFuture;

/**
 * Minimal {@link LanguageClient}: routes {@code publishDiagnostics} into a {@link DiagnosticsSink}
 * and captures server log/show-message text for the Output panel. Everything else uses LSP4J
 * defaults so a server's optional client requests degrade gracefully.
 */
public final class EditorLanguageClient implements LanguageClient {

    private final DiagnosticsSink diagnostics;
    private final java.util.List<String> log = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public EditorLanguageClient(DiagnosticsSink diagnostics) {
        this.diagnostics = diagnostics;
    }

    public java.util.List<String> log() {
        return java.util.List.copyOf(log);
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams params) {
        diagnostics.publish(params.getUri(), params.getDiagnostics());
    }

    @Override
    public void telemetryEvent(Object object) {
        // no-op
    }

    @Override
    public void showMessage(MessageParams params) {
        log.add("[show] " + params.getMessage());
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void logMessage(MessageParams params) {
        log.add("[log] " + params.getMessage());
    }
}
