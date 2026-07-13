package dev.jdesk.editor.app;

import dev.jdesk.api.ApplicationHandle;
import dev.jdesk.api.PtyHandle;
import dev.jdesk.api.PtySpec;
import dev.jdesk.editor.api.EditorErrorCode;
import dev.jdesk.editor.api.EditorException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Opens and manages real PTYs through the JDesk runtime (spec §17). Output is accumulated per
 * terminal so an agent polling {@code terminal_read} sees everything the shell produced; a bounded
 * cap keeps a runaway producer from exhausting memory. Human-facing terminals additionally stream
 * through the terminal-service output pump; this poll buffer is the agent view.
 */
public final class TerminalManager {

    public record TerminalRead(String output, boolean alive, Integer exitCode) {}

    private static final int MAX_BUFFERED_BYTES = 8 * 1024 * 1024;

    private final ApplicationHandle app;
    private final AtomicInteger counter = new AtomicInteger();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public TerminalManager(ApplicationHandle app) {
        this.app = app;
    }

    private static final class Session {
        final PtyHandle handle;
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        long droppedBytes;

        Session(PtyHandle handle) {
            this.handle = handle;
        }
    }

    public String open(Path workspaceRoot, List<String> command, int cols, int rows) {
        String id = "terminal-" + counter.incrementAndGet();
        Session session = new Session(null);
        List<String> argv = command == null || command.isEmpty()
                ? List.of(System.getenv().getOrDefault("SHELL", "/bin/zsh"), "-l")
                : command;
        PtySpec spec = new PtySpec(argv, Optional.ofNullable(workspaceRoot),
                Map.of("TERM", "xterm-256color"), cols <= 0 ? 80 : cols, rows <= 0 ? 24 : rows);
        PtyHandle handle = app.openPty(spec, bytes -> appendOutput(id, bytes));
        Session withHandle = new Session(handle);
        // Copy any output that arrived before we stored the session (unlikely, but safe).
        synchronized (session.buffer) {
            withHandle.buffer.writeBytes(session.buffer.toByteArray());
        }
        sessions.put(id, withHandle);
        return id;
    }

    private void appendOutput(String id, byte[] bytes) {
        Session session = sessions.get(id);
        if (session == null) {
            return;
        }
        synchronized (session.buffer) {
            if (session.buffer.size() + bytes.length > MAX_BUFFERED_BYTES) {
                session.droppedBytes += bytes.length;
                return;
            }
            session.buffer.writeBytes(bytes);
        }
    }

    public void write(String id, String data) {
        require(id).handle.write(data.getBytes(StandardCharsets.UTF_8));
    }

    public void resize(String id, int cols, int rows) {
        require(id).handle.resize(cols, rows);
    }

    /** Returns and clears the accumulated output, plus liveness and exit code. */
    public TerminalRead read(String id) {
        Session session = require(id);
        String output;
        synchronized (session.buffer) {
            output = session.buffer.toString(StandardCharsets.UTF_8);
            session.buffer.reset();
        }
        OptionalInt exit = session.handle.exitCode();
        return new TerminalRead(output, session.handle.isAlive(),
                exit.isPresent() ? exit.getAsInt() : null);
    }

    public void close(String id) {
        Session session = sessions.remove(id);
        if (session != null) {
            session.handle.close();
        }
    }

    private Session require(String id) {
        Session session = sessions.get(id);
        if (session == null) {
            throw new EditorException(EditorErrorCode.TARGET_NOT_FOUND, "No such terminal: " + id);
        }
        return session;
    }
}
