package dev.jdesk.editor.mcp;

import dev.jdesk.editor.core.doc.AtomicSaver;
import dev.jdesk.editor.core.doc.DocumentStore;
import dev.jdesk.editor.core.fs.FileTree;
import dev.jdesk.editor.core.fs.PathService;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Standalone MCP server over a workspace, for driving the editor tools from a real external agent
 * (codex / claude) without launching the WebView. Writes a Claude-format {@code --mcp-config} file
 * and prints {@code MCP-READY <url>} so a harness can wire an agent to it. Not part of the packaged
 * app — this is a test/integration entry point.
 *
 * <p>Usage: {@code McpLauncher <workspace> <mcp-config-out> [discovery-out]}
 */
public final class McpLauncher {

    private McpLauncher() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: McpLauncher <workspace> <mcp-config-out> [discovery-out]");
            System.exit(2);
            return;
        }
        Path workspace = Path.of(args[0]);
        Path mcpConfigOut = Path.of(args[1]);
        Path discovery = args.length > 2 ? Path.of(args[2])
                : workspace.resolve(".jdesk/mcp/discovery.json");

        PathService paths = new PathService(workspace);
        DocumentStore documents = new DocumentStore(paths, new AtomicSaver(), System::currentTimeMillis);
        CoreEditorBridge bridge = new CoreEditorBridge(paths, new FileTree(paths), documents, uri -> {});

        McpServer server = new McpServer(bridge, discovery);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));

        // Claude Code --mcp-config format: an HTTP MCP server with a bearer header.
        String config = """
                {"mcpServers":{"jdesk_editor":{"type":"http","url":"%s",\
                "headers":{"Authorization":"Bearer %s"}}}}"""
                .formatted(server.url(), server.token());
        Files.createDirectories(mcpConfigOut.getParent());
        Files.writeString(mcpConfigOut, config);

        System.out.println("MCP-READY " + server.url());
        System.out.flush();

        // Stay alive until the process is killed; the shutdown hook closes the server. (Reading
        // stdin would exit immediately under a </dev/null redirect, which is not what we want.)
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
