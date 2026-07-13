package dev.jdesk.editor.evidence;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.TreeMap;

/** Writes {@code files-before.json} / {@code files-after.json}: path, size, sha256, mtime. */
public final class FileSnapshots {

    private static final Set<String> SKIPPED_DIRS = Set.of(".git", "node_modules", ".gradle", "build");
    private static final int MAX_FILES = 20_000;

    private FileSnapshots() {}

    public static void snapshot(Path workspaceRoot, Path outFile) {
        TreeMap<String, ObjectNode> entries = new TreeMap<>();
        try {
            Files.walkFileTree(workspaceRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (SKIPPED_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (entries.size() >= MAX_FILES) {
                        return FileVisitResult.TERMINATE;
                    }
                    ObjectNode node = JsonIo.object();
                    node.put("path", workspaceRoot.relativize(file).toString());
                    node.put("sizeBytes", attrs.size());
                    node.put("sha256", Hashes.sha256(file));
                    node.put("mtimeUtc", attrs.lastModifiedTime().toInstant().toString());
                    entries.put(node.get("path").asText(), node);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Snapshot failed for " + workspaceRoot, e);
        }
        ObjectNode root = JsonIo.object();
        root.put("root", workspaceRoot.toString());
        root.put("truncated", entries.size() >= MAX_FILES);
        ArrayNode files = root.putArray("files");
        entries.values().forEach(files::add);
        JsonIo.write(outFile, root);
    }
}
