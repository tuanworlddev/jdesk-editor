package dev.jdesk.editor.app;

import dev.jdesk.api.ApplicationHandle;
import dev.jdesk.api.CapabilityGrant;
import dev.jdesk.api.CapabilitySet;
import dev.jdesk.api.CommandRegistry;
import dev.jdesk.api.JDeskApplication;
import dev.jdesk.api.LifecycleListener;
import dev.jdesk.api.WindowConfig;
import dev.jdesk.api.WindowId;
import dev.jdesk.editor.app.ipc.DocumentFacade;
import dev.jdesk.editor.app.ipc.DocumentFacadeCommands;
import dev.jdesk.editor.app.ipc.JDeskCommands;
import dev.jdesk.editor.app.ipc.WorkspaceFacade;
import dev.jdesk.editor.app.ipc.WorkspaceFacadeCommands;
import dev.jdesk.editor.mcp.McpServer;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JDesk Editor entry point. Wires the workspace/document facades into a single command registry,
 * serves the Monaco frontend over {@code jdesk://app}, and runs the native window until close.
 * An optional {@code --workspace <path>} argument opens a folder at startup (used by E2E).
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        // Asset serving is normally configured by the launcher: `gradle run` sets
        // jdesk.assets.dir; jdeskPackage sets jdesk.assets.classpath=web (classpath image). Only if
        // none is set (e.g. a bare `java -jar`) do we fall back to this module's bundled web/.
        if (System.getProperty("jdesk.assets.dir") == null
                && System.getProperty("jdesk.assets.module") == null
                && System.getProperty("jdesk.assets.classpath") == null) {
            System.setProperty("jdesk.assets.classpath", "web");
        }

        // Monaco needs style-src 'unsafe-inline' (dynamic <style> nodes) and worker-src for its
        // workers; script-src stays 'self' with no unsafe-eval (proven by the Phase-0 gate).
        System.setProperty("jdesk.security.acknowledgeUnsafeCsp", "true");

        // Packaged smoke test: boot the real app-image, confirm the window loads the editor, exit 0.
        boolean smoke = has(args, "--jdesk-smoke");
        String csp = "default-src 'self'; script-src 'self'; worker-src 'self' blob:; "
                + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; "
                + "connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'";

        AtomicReference<EditorSession> current = new AtomicReference<>();
        String startupWorkspace = argValue(args, "--workspace");
        if (startupWorkspace != null) {
            current.set(new EditorSession(java.nio.file.Path.of(startupWorkspace)));
        }

        AtomicReference<ApplicationHandle> handleRef = new AtomicReference<>();
        AtomicReference<WorkspaceWatchService> watcherRef = new AtomicReference<>();
        java.util.function.Consumer<EditorSession> onOpen = opened -> {
            current.set(opened);
            startWatcher(handleRef.get(), opened, watcherRef);
        };

        WorkspaceFacade workspace = new WorkspaceFacade(current::get, onOpen);
        DocumentFacade documents = new DocumentFacade(current::get);
        // The embedded agent uses the editor's own MCP config, written next to the MCP server.
        AtomicReference<Path> mcpConfigRef = new AtomicReference<>();
        dev.jdesk.editor.app.ipc.AgentFacade agents =
                new dev.jdesk.editor.app.ipc.AgentFacade(mcpConfigRef::get);

        CommandRegistry registry = JDeskCommands.combine(
                WorkspaceFacadeCommands.create(workspace),
                DocumentFacadeCommands.create(documents),
                dev.jdesk.editor.app.ipc.AgentFacadeCommands.create(agents));

        CapabilitySet capabilities = CapabilitySet.of(Set.of(
                CapabilityGrant.forAllWindows("editor:core")));

        // The MCP server lets agents drive the running editor. Enabled with automation (E2E) or
        // -Djdesk.editor.mcp=true. Started at onReady so agent edits can push into the live window.
        boolean mcpEnabled = Boolean.getBoolean("jdesk.editor.mcp") || Boolean.getBoolean("jdesk.automation");
        AtomicReference<McpServer> mcpRef = new AtomicReference<>();

        LifecycleListener lifecycle = new LifecycleListener() {
            @Override
            public void onReady(ApplicationHandle application) {
                if (smoke) {
                    // The window loaded the editor entry successfully; close it for a clean exit 0.
                    Thread closer = new Thread(() -> {
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        System.out.println("JDESK-EDITOR-SMOKE OK");
                        application.window(new WindowId("main")).ifPresent(w -> w.close());
                    }, "smoke-closer");
                    closer.setDaemon(true);
                    closer.start();
                    return;
                }
                // Start the filesystem watcher for the current workspace (external-change reload /
                // dirty-conflict), and re-start it whenever a new folder is opened.
                handleRef.set(application);
                EditorSession session = current.get();
                if (session != null) {
                    startWatcher(application, session, watcherRef);
                }

                if (!mcpEnabled) {
                    return;
                }
                TerminalManager terminals = new TerminalManager(application);
                AppEditorBridge bridge = new AppEditorBridge(current::get, event ->
                        application.window(new WindowId("main"))
                                .ifPresent(w -> w.events().emit("editor.docChanged", event)),
                        terminals);
                Path mcpDir = Path.of(System.getProperty("jdesk.editor.mcp.dir",
                        System.getProperty("java.io.tmpdir") + "/jdesk-editor-mcp"));
                McpServer server = new McpServer(bridge, mcpDir.resolve("discovery.json"));
                server.start();
                writeMcpConfig(mcpDir, server);
                mcpConfigRef.set(mcpDir.resolve("mcp-config.json"));
                mcpRef.set(server);
                System.out.println("MCP-READY " + server.url());
            }

            @Override
            public void onStopping() {
                McpServer server = mcpRef.get();
                if (server != null) {
                    server.close();
                }
            }
        };

        int exit = JDeskApplication.builder()
                .id("dev.jdesk.editor")
                .commands(registry)
                .capabilities(capabilities)
                .contentSecurityPolicy(csp)
                .lifecycle(lifecycle)
                .window(WindowConfig.builder()
                        .id("main")
                        .title("JDesk Editor")
                        .size(1280, 840)
                        .entry("jdesk://app/index.html")
                        .build())
                .run(args);
        System.exit(exit);
    }

    private static void startWatcher(ApplicationHandle handle, EditorSession session,
            AtomicReference<WorkspaceWatchService> watcherRef) {
        if (handle == null || session == null) {
            return;
        }
        WorkspaceWatchService previous = watcherRef.getAndSet(null);
        if (previous != null) {
            previous.close();
        }
        WorkspaceWatchService watcher = new WorkspaceWatchService(handle, session, event ->
                handle.window(new WindowId("main"))
                        .ifPresent(w -> w.events().emit("editor.externalChange", event)));
        watcher.start();
        watcherRef.set(watcher);
    }

    private static void writeMcpConfig(Path mcpDir, McpServer server) {
        try {
            java.nio.file.Files.createDirectories(mcpDir);
            String config = "{\"mcpServers\":{\"jdesk_editor\":{\"type\":\"http\",\"url\":\""
                    + server.url() + "\",\"headers\":{\"Authorization\":\"Bearer " + server.token()
                    + "\"}}}}";
            java.nio.file.Files.writeString(mcpDir.resolve("mcp-config.json"), config);
        } catch (java.io.IOException e) {
            System.err.println("Failed writing MCP config: " + e);
        }
    }

    private static boolean has(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equals(flag)) {
                return true;
            }
        }
        return false;
    }

    private static String argValue(String[] args, String name) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return null;
    }
}
