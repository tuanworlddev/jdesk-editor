package dev.jdesk.editor.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared Jackson configuration. Timestamps are ISO-8601 strings, keys are sorted so
 * evidence files are diff-stable, and output is indented for human review.
 */
public final class JsonIo {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private JsonIo() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static void write(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing " + file, e);
        }
    }

    public static JsonNode read(Path file) {
        try {
            return MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading " + file, e);
        }
    }

    /** Single-line JSON for JSONL streams. */
    public static String line(Object value) {
        try {
            return MAPPER.writer().withoutFeatures(SerializationFeature.INDENT_OUTPUT).writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed serializing JSONL line", e);
        }
    }
}
