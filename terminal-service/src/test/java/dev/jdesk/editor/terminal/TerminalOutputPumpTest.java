package dev.jdesk.editor.terminal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Terminal backpressure (spec §17 stress case, §24.1 unit target). Proves the pump delivers a
 * large stream in full with bounded in-flight events and bounded memory, and that a producer
 * outrunning an idle consumer degrades to drop-oldest (never unbounded growth).
 */
class TerminalOutputPumpTest {

    @Test
    void deliversTenMebibytesInFullUnderCreditFlowControl() {
        int total = 10 * 1024 * 1024;
        byte[] source = new byte[total];
        for (int i = 0; i < total; i++) {
            source[i] = (byte) (i % 251); // deterministic, non-trivial pattern
        }
        TerminalOutputPump pump = new TerminalOutputPump();

        ByteArrayOutputStream delivered = new ByteArrayOutputStream(total);
        AtomicLong maxInFlight = new AtomicLong();
        int offset = 0;
        int producerChunk = 64 * 1024;
        // Simulate: producer appends bursts; a consumer drains and immediately acks each chunk.
        while (offset < total || pump.pendingBytes() > 0) {
            if (offset < total) {
                int n = Math.min(producerChunk, total - offset);
                byte[] burst = new byte[n];
                System.arraycopy(source, offset, burst, 0, n);
                pump.append(burst);
                offset += n;
            }
            int before = pump.availableCredits();
            pump.drain(chunk -> {
                delivered.write(chunk, 0, chunk.length);
                assertThat(chunk.length).isLessThanOrEqualTo(TerminalOutputPump.DEFAULT_MAX_CHUNK_BYTES);
            });
            maxInFlight.accumulateAndGet(before - pump.availableCredits(), Math::max);
            // Consumer acknowledges everything it received this round.
            while (pump.availableCredits() < TerminalOutputPump.DEFAULT_CREDIT_WINDOW) {
                pump.ack();
            }
        }

        assertThat(delivered.size()).isEqualTo(total);
        assertThat(delivered.toByteArray()).isEqualTo(source);
        assertThat(pump.droppedBytes()).isZero();
        // In-flight events never exceeded the credit window (queue-safe).
        assertThat(maxInFlight.get()).isLessThanOrEqualTo(TerminalOutputPump.DEFAULT_CREDIT_WINDOW);
    }

    @Test
    void boundsMemoryAndDropsOldestWhenConsumerNeverAcks() {
        // Small ring so we can overflow it deterministically.
        TerminalOutputPump pump = new TerminalOutputPump(1024, 4096, 4);
        for (int i = 0; i < 100; i++) {
            byte[] kib = new byte[1024];
            java.util.Arrays.fill(kib, (byte) i);
            pump.append(kib);
        }
        // Ring never grows past its capacity regardless of how much was appended.
        assertThat(pump.pendingBytes()).isLessThanOrEqualTo(4096);
        assertThat(pump.droppedBytes()).isGreaterThan(0);
        assertThat(pump.truncationEvents()).isGreaterThan(0);
    }

    @Test
    void neverEmitsMoreThanTheCreditWindowWithoutAcks() {
        TerminalOutputPump pump = new TerminalOutputPump(1024, 1024 * 1024, 3);
        byte[] data = new byte[100 * 1024];
        pump.append(data);
        AtomicLong emitted = new AtomicLong();
        pump.drain(chunk -> emitted.incrementAndGet());
        // Only credit-window chunks emit before any ack.
        assertThat(emitted.get()).isEqualTo(3);
        assertThat(pump.availableCredits()).isZero();
    }

    @Test
    void preservesMultibyteContentAcrossChunkBoundaries() {
        TerminalOutputPump pump = new TerminalOutputPump(8, 1024 * 1024, 1000);
        String text = "héllo✓wörld🌍done"; // multibyte UTF-8 that will split across 8-byte chunks
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        pump.append(bytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (pump.pendingBytes() > 0) {
            pump.drain(chunk -> out.write(chunk, 0, chunk.length));
            while (pump.availableCredits() < 1000) {
                pump.ack();
            }
        }
        // Reassembled bytes decode back to the original text (xterm's decoder does this in the UI).
        assertThat(new String(out.toByteArray(), StandardCharsets.UTF_8)).isEqualTo(text);
    }
}
