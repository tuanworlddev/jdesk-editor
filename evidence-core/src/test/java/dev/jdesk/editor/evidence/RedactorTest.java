package dev.jdesk.editor.evidence;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The redactor must remove every planted secret; leaking one into artifacts is unacceptable. */
class RedactorTest {

    @Test
    void masksRegisteredLiteralSecret() {
        Redactor redactor = new Redactor();
        redactor.registerSecret("mcp-token", "abcdef0123456789deadbeef");
        String out = redactor.redact("Authorization header used abcdef0123456789deadbeef here");
        assertThat(out).doesNotContain("abcdef0123456789deadbeef");
        assertThat(out).contains("[REDACTED:mcp-token]");
    }

    @Test
    void masksBearerTokens() {
        Redactor redactor = new Redactor();
        String out = redactor.redact("curl -H 'Authorization: Bearer sk-ant-api03-XYZ1234567890abcdef'");
        assertThat(out).doesNotContain("XYZ1234567890abcdef");
        assertThat(out).contains("[REDACTED]");
    }

    @Test
    void masksApiKeyAssignments() {
        Redactor redactor = new Redactor();
        assertThat(redactor.redact("ANTHROPIC_API_KEY=sk-ant-secretvalue12345"))
                .doesNotContain("secretvalue12345");
    }

    @Property
    void neverLeaksARegisteredSecret(
            @ForAll @StringLength(min = 12, max = 40) @AlphaChars String secret,
            @ForAll @StringLength(max = 60) String surrounding) {
        Redactor redactor = new Redactor();
        redactor.registerSecret("planted", secret);
        String haystack = surrounding + secret + surrounding;
        assertThat(redactor.redact(haystack)).doesNotContain(secret);
    }
}
