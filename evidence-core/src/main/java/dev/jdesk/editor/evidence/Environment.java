package dev.jdesk.editor.evidence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Where a run executes: the editor repo, the JDesk framework checkout, and the pinned
 * framework SHA the run must match. Production callers use {@link #fromRepo}; tests build
 * their own with temp git repositories — there is deliberately no way to skip the pin check.
 *
 * @param repoRoot editor repository root
 * @param artifactsDir directory receiving {@code test-runs/<runId>/}
 * @param jdeskSource JDesk framework checkout
 * @param jdeskPinnedSha full commit SHA the JDesk checkout must be at, clean
 */
public record Environment(Path repoRoot, Path artifactsDir, Path jdeskSource, String jdeskPinnedSha) {

    public static Environment fromRepo(Path repoRoot) {
        Path props = repoRoot.resolve("gradle.properties");
        Properties properties = new Properties();
        try (var in = Files.newInputStream(props)) {
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + props, e);
        }
        String source = required(properties, "jdeskSource", props);
        String pin = required(properties, "jdeskPinnedSha", props);
        return new Environment(
                repoRoot,
                repoRoot.resolve("artifacts"),
                repoRoot.resolve(source).normalize(),
                pin);
    }

    private static String required(Properties properties, String key, Path source) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing '" + key + "' in " + source);
        }
        return value.trim();
    }
}
