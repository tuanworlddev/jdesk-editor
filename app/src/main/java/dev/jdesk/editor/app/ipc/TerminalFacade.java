package dev.jdesk.editor.app.ipc;

import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.RequiresCapability;
import dev.jdesk.editor.app.EditorSession;
import dev.jdesk.editor.app.TerminalManager;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Typed IPC facade for the interactive terminal (spec §17). xterm.js in the frontend polls
 * {@code terminal.read} for output and sends keystrokes via {@code terminal.write}; the PTY is a
 * real login shell in the workspace root.
 */
public final class TerminalFacade {

    private final Supplier<TerminalManager> terminalsRef;
    private final Supplier<EditorSession> session;

    public TerminalFacade(Supplier<TerminalManager> terminalsRef, Supplier<EditorSession> session) {
        this.terminalsRef = terminalsRef;
        this.session = session;
    }

    private TerminalManager terminals() {
        TerminalManager t = terminalsRef.get();
        if (t == null) {
            throw new dev.jdesk.editor.api.EditorException(
                    dev.jdesk.editor.api.EditorErrorCode.AGENT_NOT_AVAILABLE, "Terminals not ready");
        }
        return t;
    }

    @DesktopCommand("terminal.open")
    @RequiresCapability("editor:core")
    public CompletionStage<TerminalDtos.Terminal> open(
            TerminalDtos.OpenRequest request, InvocationContext context) {
        EditorSession current = session.get();
        String id = terminals().open(current == null ? null : current.root(), List.of(),
                request.cols(), request.rows());
        return CompletableFuture.completedFuture(new TerminalDtos.Terminal(id));
    }

    @DesktopCommand("terminal.write")
    @RequiresCapability("editor:core")
    public CompletionStage<TerminalDtos.Ack> write(
            TerminalDtos.WriteRequest request, InvocationContext context) {
        terminals().write(request.terminalId(), request.data());
        return CompletableFuture.completedFuture(new TerminalDtos.Ack(true));
    }

    @DesktopCommand("terminal.read")
    @RequiresCapability("editor:core")
    public CompletionStage<TerminalDtos.Output> read(
            TerminalDtos.ReadRequest request, InvocationContext context) {
        TerminalManager.TerminalRead read = terminals().read(request.terminalId());
        return CompletableFuture.completedFuture(new TerminalDtos.Output(
                read.output(), read.alive(),
                read.exitCode() == null ? -1 : read.exitCode()));
    }

    @DesktopCommand("terminal.resize")
    @RequiresCapability("editor:core")
    public CompletionStage<TerminalDtos.Ack> resize(
            TerminalDtos.ResizeRequest request, InvocationContext context) {
        terminals().resize(request.terminalId(), request.cols(), request.rows());
        return CompletableFuture.completedFuture(new TerminalDtos.Ack(true));
    }

    @DesktopCommand("terminal.close")
    @RequiresCapability("editor:core")
    public CompletionStage<TerminalDtos.Ack> close(
            TerminalDtos.ReadRequest request, InvocationContext context) {
        terminals().close(request.terminalId());
        return CompletableFuture.completedFuture(new TerminalDtos.Ack(true));
    }
}
