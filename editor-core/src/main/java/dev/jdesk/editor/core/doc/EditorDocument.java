package dev.jdesk.editor.core.doc;

import dev.jdesk.editor.api.LineEnding;
import dev.jdesk.editor.api.WorkspacePath;

import java.nio.charset.Charset;

/**
 * Authoritative in-memory state of one open document (spec §11.1). The Java core — not the WebView
 * — owns the content of record; the frontend Monaco model is a synchronized view. Mutations go
 * through {@link DocumentStore} so version, hashes, dirty flag, and lease stay consistent.
 */
public final class EditorDocument {

    private final WorkspacePath path;
    private final Charset encoding;

    private String content;
    private long version;
    private String contentHash;
    private String diskHash;
    private LineEnding lineEnding;
    private boolean dirty;
    private long lastSavedAtEpochMs;
    private ExternalChangeState externalChangeState = ExternalChangeState.NONE;
    private Lease lease = Lease.NONE;

    EditorDocument(WorkspacePath path, String content, String diskHash, Charset encoding,
            LineEnding lineEnding, long nowEpochMs) {
        this.path = path;
        this.content = content;
        this.contentHash = Hashing.sha256(content);
        this.diskHash = diskHash;
        this.encoding = encoding;
        this.lineEnding = lineEnding;
        this.version = 1;
        this.dirty = false;
        this.lastSavedAtEpochMs = nowEpochMs;
    }

    public WorkspacePath path() {
        return path;
    }

    public String uri() {
        return path.uri();
    }

    public String content() {
        return content;
    }

    public long version() {
        return version;
    }

    public String contentHash() {
        return contentHash;
    }

    public String diskHash() {
        return diskHash;
    }

    public Charset encoding() {
        return encoding;
    }

    public LineEnding lineEnding() {
        return lineEnding;
    }

    public boolean dirty() {
        return dirty;
    }

    public long lastSavedAtEpochMs() {
        return lastSavedAtEpochMs;
    }

    public ExternalChangeState externalChangeState() {
        return externalChangeState;
    }

    public Lease lease() {
        return lease;
    }

    // ---- mutations (package-private: only DocumentStore drives them) ----

    void setContent(String newContent, boolean markDirty) {
        this.content = newContent;
        this.contentHash = Hashing.sha256(newContent);
        this.version++;
        if (markDirty) {
            this.dirty = true;
        }
    }

    void markSaved(String newDiskHash, long nowEpochMs) {
        this.diskHash = newDiskHash;
        this.dirty = false;
        this.lastSavedAtEpochMs = nowEpochMs;
        this.externalChangeState = ExternalChangeState.NONE;
    }

    void refreshFromDisk(String newContent, String newDiskHash) {
        this.content = newContent;
        this.contentHash = Hashing.sha256(newContent);
        this.diskHash = newDiskHash;
        this.version++;
        this.dirty = false;
        this.externalChangeState = ExternalChangeState.RELOADED;
    }

    void setExternalChangeState(ExternalChangeState state) {
        this.externalChangeState = state;
    }

    void setLineEnding(LineEnding lineEnding) {
        this.lineEnding = lineEnding;
    }

    void setLease(Lease lease) {
        this.lease = lease;
    }
}
