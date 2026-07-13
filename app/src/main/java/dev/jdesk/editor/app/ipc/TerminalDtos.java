package dev.jdesk.editor.app.ipc;

/** Wire DTOs for the interactive terminal. Public records of restricted types only. */
public final class TerminalDtos {

    private TerminalDtos() {}

    public record OpenRequest(int cols, int rows) {}

    public record Terminal(String terminalId) {}

    public record WriteRequest(String terminalId, String data) {}

    public record ReadRequest(String terminalId) {}

    public record ResizeRequest(String terminalId, int cols, int rows) {}

    public record Output(String data, boolean alive, int exitCode) {}

    public record Ack(boolean ok) {}
}
