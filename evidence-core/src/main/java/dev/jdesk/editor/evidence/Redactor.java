package dev.jdesk.editor.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Scrubs secrets from everything the harness writes. Two layers:
 *
 * <ul>
 *   <li>literal replacement of secrets the harness itself knows (automation token,
 *       MCP bearer) — registered via {@link #registerSecret};</li>
 *   <li>conservative pattern replacement for common token shapes.</li>
 * </ul>
 *
 * <p>Patterns are deliberately narrow: over-redaction of ordinary code and logs would make
 * evidence useless, so only high-confidence token shapes are masked.
 */
public final class Redactor {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]{16,}"),
            Pattern.compile("\\b(sk-)[A-Za-z0-9_-]{16,}\\b"),
            Pattern.compile("\\b(sk-ant-)[A-Za-z0-9_-]{16,}\\b"),
            Pattern.compile("(?i)\\b((?:api[_-]?key|auth[_-]?token|access[_-]?token|secret|password)\\s*[=:]\\s*)[^\\s\"']{8,}"));

    private final Map<String, String> literals = new ConcurrentHashMap<>();

    /** Registers a literal secret; every future redact call masks it as {@code [REDACTED:name]}. */
    public void registerSecret(String name, String value) {
        if (value != null && value.length() >= 8) {
            literals.put(value, "[REDACTED:" + name + "]");
        }
    }

    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Map.Entry<String, String> entry : literals.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        for (Pattern pattern : PATTERNS) {
            result = pattern.matcher(result).replaceAll(m ->
                    java.util.regex.Matcher.quoteReplacement(m.group(1)) + "[REDACTED]");
        }
        return result;
    }

    /** Names of registered literal secrets, for diagnostics (never the values). */
    public List<String> registeredSecretNames() {
        return new ArrayList<>(literals.values());
    }
}
