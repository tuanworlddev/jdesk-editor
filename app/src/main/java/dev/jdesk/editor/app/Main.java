package dev.jdesk.editor.app;

import dev.jdesk.api.CapabilityGrant;
import dev.jdesk.api.CapabilitySet;
import dev.jdesk.api.CommandRegistry;
import dev.jdesk.api.JDeskApplication;
import dev.jdesk.api.WindowConfig;
import dev.jdesk.editor.app.ipc.DocumentFacade;
import dev.jdesk.editor.app.ipc.DocumentFacadeCommands;
import dev.jdesk.editor.app.ipc.JDeskCommands;
import dev.jdesk.editor.app.ipc.WorkspaceFacade;
import dev.jdesk.editor.app.ipc.WorkspaceFacadeCommands;

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
        // Monaco needs style-src 'unsafe-inline' (dynamic <style> nodes) and worker-src for its
        // workers; script-src stays 'self' with no unsafe-eval (proven by the Phase-0 gate).
        System.setProperty("jdesk.security.acknowledgeUnsafeCsp", "true");
        String csp = "default-src 'self'; script-src 'self'; worker-src 'self' blob:; "
                + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; "
                + "connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'";

        AtomicReference<EditorSession> current = new AtomicReference<>();
        String startupWorkspace = argValue(args, "--workspace");
        if (startupWorkspace != null) {
            current.set(new EditorSession(java.nio.file.Path.of(startupWorkspace)));
        }

        WorkspaceFacade workspace = new WorkspaceFacade(current::get, current::set);
        DocumentFacade documents = new DocumentFacade(current::get);

        CommandRegistry registry = JDeskCommands.combine(
                WorkspaceFacadeCommands.create(workspace),
                DocumentFacadeCommands.create(documents));

        CapabilitySet capabilities = CapabilitySet.of(Set.of(
                CapabilityGrant.forAllWindows("editor:core")));

        int exit = JDeskApplication.builder()
                .id("dev.jdesk.editor")
                .commands(registry)
                .capabilities(capabilities)
                .contentSecurityPolicy(csp)
                .window(WindowConfig.builder()
                        .id("main")
                        .title("JDesk Editor")
                        .size(1280, 840)
                        .entry("jdesk://app/index.html")
                        .build())
                .run(args);
        System.exit(exit);
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
