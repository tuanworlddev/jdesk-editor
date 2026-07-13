package dev.jdesk.editor.lsp;

import dev.jdesk.editor.api.EditorErrorCode;
import dev.jdesk.editor.api.EditorException;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.PublishDiagnosticsCapabilities;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.launch.LSPLauncher;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One running language server bound to a workspace (spec §18). Performs the LSP initialize
 * lifecycle and synchronizes open/change/save/close with document versions; diagnostics flow into
 * the {@link DiagnosticsSink}. Uses full-text document sync for simplicity and correctness.
 */
public final class LspSession implements AutoCloseable {

    private final LspServerConfig config;
    private final Path workspace;
    private final DiagnosticsSink diagnostics;

    private Process process;
    private LanguageServer server;

    public LspSession(LspServerConfig config, Path workspace, DiagnosticsSink diagnostics) {
        this.config = config;
        this.workspace = workspace;
        this.diagnostics = diagnostics;
    }

    public String languageId() {
        return config.languageId();
    }

    /** Starts the server process and completes the initialize handshake. */
    public void start() {
        try {
            process = new ProcessBuilder(config.command())
                    .directory(workspace.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (Exception e) {
            throw new EditorException(EditorErrorCode.PROCESS_FAILED,
                    "Cannot start language server for " + config.languageId(), e);
        }
        EditorLanguageClient client = new EditorLanguageClient(diagnostics);
        Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(
                client, process.getInputStream(), process.getOutputStream());
        this.server = launcher.getRemoteProxy();
        launcher.startListening();

        InitializeParams params = new InitializeParams();
        params.setProcessId((int) ProcessHandle.current().pid());
        params.setRootUri(workspace.toUri().toString());
        ClientCapabilities capabilities = new ClientCapabilities();
        TextDocumentClientCapabilities textCaps = new TextDocumentClientCapabilities();
        PublishDiagnosticsCapabilities publish = new PublishDiagnosticsCapabilities();
        publish.setRelatedInformation(true);
        textCaps.setPublishDiagnostics(publish);
        capabilities.setTextDocument(textCaps);
        params.setCapabilities(capabilities);
        try {
            server.initialize(params).get(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EditorException(EditorErrorCode.INTERNAL_ERROR, "interrupted during LSP initialize", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new EditorException(EditorErrorCode.PROCESS_FAILED,
                    "LSP initialize failed for " + config.languageId(), e);
        }
        server.initialized(new InitializedParams());
    }

    public void didOpen(String uri, int version, String text) {
        TextDocumentItem item = new TextDocumentItem(uri, config.languageId(), version, text);
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(item));
    }

    public void didChange(String uri, int version, String fullText) {
        VersionedTextDocumentIdentifier id = new VersionedTextDocumentIdentifier(uri, version);
        TextDocumentContentChangeEvent change = new TextDocumentContentChangeEvent(fullText);
        server.getTextDocumentService().didChange(
                new DidChangeTextDocumentParams(id, List.of(change)));
    }

    public void didSave(String uri) {
        server.getTextDocumentService().didSave(
                new DidSaveTextDocumentParams(new TextDocumentIdentifier(uri)));
    }

    public void didClose(String uri) {
        server.getTextDocumentService().didClose(
                new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri)));
    }

    @Override
    public void close() {
        try {
            if (server != null) {
                server.shutdown().get(5, TimeUnit.SECONDS);
                server.exit();
            }
        } catch (Exception ignored) {
            // fall through to destroy
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
