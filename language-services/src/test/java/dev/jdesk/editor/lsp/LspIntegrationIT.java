package dev.jdesk.editor.lsp;

import org.eclipse.lsp4j.Diagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Real LSP acceptance (spec §18, §24.2; a DoD requirement — one real TS/JS server and one real
 * Python server). Starts the pinned language servers, opens a document with a deliberate type
 * error, and asserts a real diagnostic is published. Skipped only if the pinned servers are not
 * installed (run {@code npm install --prefix tools/lsp}).
 */
class LspIntegrationIT {

    private static final Path TOOLS_LSP_BIN =
            Path.of(System.getProperty("jdesk.toolsLspBin",
                    Path.of("").toAbsolutePath().getParent() + "/tools/lsp/node_modules/.bin"))
                    .toAbsolutePath();

    private boolean serverPresent(String binName) {
        return Files.exists(TOOLS_LSP_BIN.resolve(binName));
    }

    private List<Diagnostic> awaitDiagnostics(DiagnosticsSink sink, String uri, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<Diagnostic> current = sink.forUri(uri);
            if (!current.isEmpty()) {
                return current;
            }
            Thread.sleep(200);
        }
        return sink.forUri(uri);
    }

    @Test
    void typescriptServerPublishesATypeErrorDiagnostic(@TempDir Path workspace) throws Exception {
        assumeThat(serverPresent("typescript-language-server")).isTrue();
        // A tsconfig makes the server treat the file as a real TS project.
        Files.writeString(workspace.resolve("tsconfig.json"),
                "{\"compilerOptions\":{\"strict\":true}}");
        Path file = workspace.resolve("bad.ts");
        Files.writeString(file, "const n: number = \"not a number\";\n");
        String uri = file.toUri().toString();

        DiagnosticsSink sink = new DiagnosticsSink();
        AtomicReference<String> updatedUri = new AtomicReference<>();
        sink.onUpdate((u, d) -> updatedUri.set(u));

        try (LspSession session = new LspSession(
                LspServerConfig.typescript(TOOLS_LSP_BIN.toString()), workspace, sink)) {
            session.start();
            session.didOpen(uri, 1, Files.readString(file));
            List<Diagnostic> diagnostics = awaitDiagnostics(sink, uri, 30_000);

            assertThat(diagnostics).isNotEmpty();
            assertThat(diagnostics).anySatisfy(d ->
                    assertThat(d.getMessage().toLowerCase()).containsAnyOf("not assignable", "type"));
            assertThat(updatedUri.get()).isEqualTo(uri);
        }
    }

    @Test
    void pyrightServerPublishesATypeErrorDiagnostic(@TempDir Path workspace) throws Exception {
        assumeThat(serverPresent("pyright-langserver")).isTrue();
        Path file = workspace.resolve("bad.py");
        Files.writeString(file, "x: int = \"not an int\"\n");
        String uri = file.toUri().toString();

        DiagnosticsSink sink = new DiagnosticsSink();
        try (LspSession session = new LspSession(
                LspServerConfig.pyright(TOOLS_LSP_BIN.toString()), workspace, sink)) {
            session.start();
            session.didOpen(uri, 1, Files.readString(file));
            List<Diagnostic> diagnostics = awaitDiagnostics(sink, uri, 30_000);

            assertThat(diagnostics).isNotEmpty();
        }
    }
}
