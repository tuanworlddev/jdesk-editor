package dev.jdesk.editor.core.doc;

import dev.jdesk.editor.api.EditorErrorCode;
import dev.jdesk.editor.api.EditorException;
import dev.jdesk.editor.api.LineEnding;
import dev.jdesk.editor.api.WorkspacePath;
import dev.jdesk.editor.api.wire.TextEditDto;
import dev.jdesk.editor.core.fs.PathService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative store of open documents (spec §11). Owns the version / content-hash / dirty / lease
 * state machine and is the only place those transition. Human edits and agent edit transactions
 * both funnel through {@link #applyEdits}, which enforces optimistic-concurrency versioning: a
 * stale {@code expectedVersion} is rejected with {@code DOCUMENT_VERSION_CONFLICT} and no partial
 * write occurs. Content is held with LF newlines internally; the original line-ending style is
 * preserved for save.
 */
public final class DocumentStore {

    /** Result of a successful edit: the new version and content hash to reconcile with the client. */
    public record EditResult(long version, String contentHash) {}

    private final PathService paths;
    private final AtomicSaver saver;
    private final Clock clock;
    private final Map<String, EditorDocument> byUri = new ConcurrentHashMap<>();

    @FunctionalInterface
    public interface Clock {
        long nowEpochMs();
    }

    public DocumentStore(PathService paths, AtomicSaver saver, Clock clock) {
        this.paths = paths;
        this.saver = saver;
        this.clock = clock;
    }

    public DocumentStore(PathService paths) {
        this(paths, new AtomicSaver(), System::currentTimeMillis);
    }

    /** Opens (or returns the already-open) document at the given workspace-relative path. */
    public synchronized EditorDocument open(String relativePath) {
        WorkspacePath path = paths.resolveExisting(relativePath);
        EditorDocument existing = byUri.get(path.uri());
        if (existing != null) {
            return existing;
        }
        try {
            byte[] bytes = Files.readAllBytes(path.absolute());
            String raw = new String(bytes, StandardCharsets.UTF_8);
            LineEnding lineEnding = LineEnding.detect(raw);
            String normalized = raw.replace("\r\n", "\n");
            EditorDocument document = new EditorDocument(path, normalized, Hashing.sha256(bytes),
                    StandardCharsets.UTF_8, lineEnding, clock.nowEpochMs());
            byUri.put(path.uri(), document);
            return document;
        } catch (IOException e) {
            throw new EditorException(EditorErrorCode.TARGET_NOT_FOUND,
                    "Cannot read " + path.relative(), e);
        }
    }

    /** Creates a new empty file on disk (making any missing parent directories) and opens it. */
    public synchronized EditorDocument create(String relativePath) {
        paths.ensureParentDirectories(relativePath);
        WorkspacePath path = paths.resolveForCreate(relativePath);
        try {
            if (Files.exists(path.absolute())) {
                throw new EditorException(EditorErrorCode.INVALID_ARGUMENT,
                        "File already exists: " + path.relative());
            }
            Files.writeString(path.absolute(), "");
        } catch (IOException e) {
            throw new EditorException(EditorErrorCode.INTERNAL_ERROR,
                    "Cannot create " + path.relative(), e);
        }
        return open(relativePath);
    }

    public Optional<EditorDocument> find(String uri) {
        return Optional.ofNullable(byUri.get(uri));
    }

    /**
     * Applies an edit batch under optimistic concurrency. {@code expectedVersion} must match the
     * document's current version or the whole batch is rejected (no partial write). The claimed
     * writer must be compatible with the current lease (human always allowed; an agent must hold
     * or be able to take the lease — a human lease blocks a conflicting agent).
     */
    public synchronized EditResult applyEdits(String uri, long expectedVersion,
            List<TextEditDto> edits, Lease writer) {
        EditorDocument document = require(uri);
        if (document.version() != expectedVersion) {
            throw new EditorException(EditorErrorCode.DOCUMENT_VERSION_CONFLICT,
                    "Expected version " + expectedVersion + " but document is at " + document.version());
        }
        enforceLease(document, writer);
        String updated = TextEdits.apply(document.content(), edits);
        document.setContent(updated, true);
        return new EditResult(document.version(), document.contentHash());
    }

    /**
     * Replaces the entire content (used for a huge-paste bulk resync arriving via the asset route).
     * Still version-checked so a concurrent edit is not lost.
     */
    public synchronized EditResult replaceContent(String uri, long expectedVersion, String content,
            Lease writer) {
        EditorDocument document = require(uri);
        if (document.version() != expectedVersion) {
            throw new EditorException(EditorErrorCode.DOCUMENT_VERSION_CONFLICT,
                    "Expected version " + expectedVersion + " but document is at " + document.version());
        }
        enforceLease(document, writer);
        document.setContent(content.replace("\r\n", "\n"), true);
        return new EditResult(document.version(), document.contentHash());
    }

    /** Persists the document atomically, verifying the on-disk hash has not drifted. */
    public synchronized AtomicSaver.SaveOutcome save(String uri, long expectedVersion, boolean force) {
        EditorDocument document = require(uri);
        if (document.version() != expectedVersion) {
            throw new EditorException(EditorErrorCode.DOCUMENT_VERSION_CONFLICT,
                    "Save expected version " + expectedVersion + " but document is at " + document.version());
        }
        String expectedDiskHash = force ? null : document.diskHash();
        AtomicSaver.SaveOutcome outcome = saver.save(document.path(), document.content(),
                document.encoding(), document.lineEnding(), expectedDiskHash);
        document.markSaved(outcome.diskHash(), outcome.savedAtEpochMs());
        return outcome;
    }

    /** Acquires or transfers the lease. Human takes priority and may interrupt an agent (spec §11.2). */
    public synchronized void acquireLease(String uri, Lease writer) {
        EditorDocument document = require(uri);
        enforceLease(document, writer);
        document.setLease(writer);
    }

    public synchronized void releaseLease(String uri) {
        EditorDocument document = require(uri);
        document.setLease(Lease.NONE);
    }

    /**
     * Reconciles a document with a changed backing file. A clean document is refreshed and reported
     * RELOADED; a dirty document is marked CONFLICT and never overwritten (spec §16).
     */
    public synchronized ExternalChangeState onExternalChange(String uri, String newDiskContent,
            String newDiskHash) {
        EditorDocument document = byUri.get(uri);
        if (document == null) {
            return ExternalChangeState.NONE;
        }
        if (document.diskHash().equals(newDiskHash)) {
            return document.externalChangeState(); // our own save, or no real change
        }
        if (document.dirty()) {
            document.setExternalChangeState(ExternalChangeState.CONFLICT);
            return ExternalChangeState.CONFLICT;
        }
        document.refreshFromDisk(newDiskContent.replace("\r\n", "\n"), newDiskHash);
        return ExternalChangeState.RELOADED;
    }

    public synchronized void onExternalDelete(String uri) {
        EditorDocument document = byUri.get(uri);
        if (document != null) {
            document.setExternalChangeState(ExternalChangeState.DELETED);
        }
    }

    public synchronized void close(String uri) {
        byUri.remove(uri);
    }

    public int openCount() {
        return byUri.size();
    }

    // ---- internals ----

    private EditorDocument require(String uri) {
        EditorDocument document = byUri.get(uri);
        if (document == null) {
            throw new EditorException(EditorErrorCode.TARGET_NOT_FOUND, "Document is not open");
        }
        return document;
    }

    private static void enforceLease(EditorDocument document, Lease writer) {
        Lease current = document.lease();
        if (writer.kind() == Lease.Kind.AGENT
                && current.kind() == Lease.Kind.HUMAN) {
            throw new EditorException(EditorErrorCode.EDIT_LEASE_CONFLICT,
                    "A human holds the edit lease; agent write refused");
        }
        if (writer.kind() == Lease.Kind.AGENT
                && current.kind() == Lease.Kind.AGENT
                && !current.ownerId().equals(writer.ownerId())) {
            throw new EditorException(EditorErrorCode.EDIT_LEASE_CONFLICT,
                    "Another agent holds the edit lease");
        }
    }
}
