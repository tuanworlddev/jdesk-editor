package dev.jdesk.editor.app.ipc;

import dev.jdesk.editor.api.wire.TextEditDto;

import java.util.List;

/** Wire DTOs for document open/sync/save. Public records; edits reference the editor-api record. */
public final class DocumentDtos {

    private DocumentDtos() {}

    public record OpenDocRequest(String relPath) {}

    /** Full document snapshot; content flows Java→JS uncapped. */
    public record DocSnapshot(String uri, String relPath, long version, String content,
            String contentHash, String lineEnding, boolean dirty) {}

    /** Human edit batch from Monaco. {@code baseVersion} is optimistic-concurrency guarded. */
    public record ChangeRequest(String uri, long baseVersion, List<TextEditDto> edits, long seq) {}

    public record ChangeAck(long version, String contentHash, boolean resyncRequired) {}

    public record SaveRequest(String uri, long expectedVersion, boolean force) {}

    public record SaveResult(long version, String diskHash, long savedAtEpochMs) {}

    public record CloseRequest(String uri, boolean discardDirty) {}

    public record CloseResult(boolean closed) {}
}
