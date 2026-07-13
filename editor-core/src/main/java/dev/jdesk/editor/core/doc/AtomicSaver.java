package dev.jdesk.editor.core.doc;

import dev.jdesk.editor.api.EditorErrorCode;
import dev.jdesk.editor.api.EditorException;
import dev.jdesk.editor.api.LineEnding;
import dev.jdesk.editor.api.WorkspacePath;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Crash-safe file replacement (spec §11.4): write to a temp file in the same directory, flush and
 * close it, then atomically replace the target. Required permissions are preserved and the on-disk
 * hash is re-checked immediately before replacement so a concurrent external write is not silently
 * clobbered. The provided {@link WorkspacePath} is already canonical and workspace-contained
 * (produced by {@code PathService}), so no traversal check is repeated here.
 */
public final class AtomicSaver {

    /** @param diskHash SHA-256 of the newly written file, for self-save watcher suppression. */
    public record SaveOutcome(String diskHash, long savedAtEpochMs) {}

    private final Clock clock;

    public AtomicSaver(Clock clock) {
        this.clock = clock;
    }

    public AtomicSaver() {
        this(System::currentTimeMillis);
    }

    /** Clock seam so tests get deterministic timestamps. */
    @FunctionalInterface
    public interface Clock {
        long nowEpochMs();
    }

    /**
     * Persists {@code content} to {@code target}, re-checking {@code expectedDiskHash} first when
     * present (null skips the check, e.g. first save of a new file). Line endings are materialized
     * to the document's style so the bytes are faithful.
     *
     * @throws EditorException with {@code DOCUMENT_VERSION_CONFLICT} if the file on disk changed
     */
    public SaveOutcome save(WorkspacePath target, String content, Charset encoding,
            LineEnding lineEnding, String expectedDiskHash) {
        Path file = target.absolute();
        Path dir = file.getParent();
        byte[] bytes = materialize(content, lineEnding).getBytes(encoding);
        try {
            if (expectedDiskHash != null && Files.exists(file)) {
                String current = Hashing.sha256(Files.readAllBytes(file));
                if (!current.equals(expectedDiskHash)) {
                    throw new EditorException(EditorErrorCode.DOCUMENT_VERSION_CONFLICT,
                            "File changed on disk since last read: " + target.relative());
                }
            }
            Set<PosixFilePermission> perms = readPermissions(file);
            Path tmp = Files.createTempFile(dir, "." + file.getFileName(), ".jdesk-tmp");
            try {
                Files.write(tmp, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                if (perms != null) {
                    Files.setPosixFilePermissions(tmp, perms);
                }
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicUnsupported) {
                    // Fall back to a non-atomic replace where the filesystem refuses ATOMIC_MOVE.
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
            return new SaveOutcome(Hashing.sha256(bytes), clock.nowEpochMs());
        } catch (IOException e) {
            throw new EditorException(EditorErrorCode.INTERNAL_ERROR,
                    "Failed to save " + target.relative(), e);
        }
    }

    private static String materialize(String content, LineEnding lineEnding) {
        // Content is held with LF newlines internally; convert to the target style on write.
        String normalized = content.replace("\r\n", "\n");
        return lineEnding == LineEnding.CRLF ? normalized.replace("\n", "\r\n") : normalized;
    }

    private static Set<PosixFilePermission> readPermissions(Path file) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return Files.getPosixFilePermissions(file);
        } catch (UnsupportedOperationException e) {
            return null; // non-POSIX filesystem (e.g. Windows)
        }
    }
}
