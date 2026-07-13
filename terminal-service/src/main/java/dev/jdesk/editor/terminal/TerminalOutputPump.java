package dev.jdesk.editor.terminal;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Flow-controlled coalescing buffer between a PTY and the WebView (spec §17). The PTY callback
 * thread {@link #append}s raw bytes into a bounded ring; a pump {@link #drain}s them into
 * chunks no larger than {@code maxChunkBytes}, but only while emit credits remain. The frontend
 * acknowledges consumed chunks ({@link #ack}), replenishing credits. This keeps at most
 * {@code creditWindow} events in flight, so the framework's 256-slot event queue never overflows,
 * and caps retained memory so a runaway producer cannot exhaust the heap.
 *
 * <p>UTF-8 is not decoded here: raw bytes are forwarded and xterm.js's decoder reassembles
 * multibyte sequences across chunk boundaries, so splitting anywhere is safe.
 */
public final class TerminalOutputPump {

    public static final int DEFAULT_MAX_CHUNK_BYTES = 32 * 1024;
    public static final int DEFAULT_RING_CAPACITY = 4 * 1024 * 1024;
    public static final int DEFAULT_CREDIT_WINDOW = 16;

    /** A chunk to deliver to the frontend as one event. */
    @FunctionalInterface
    public interface ChunkSink {
        void emit(byte[] chunk);
    }

    private final int maxChunkBytes;
    private final int ringCapacity;
    private final int creditWindow;

    private final Deque<byte[]> ring = new ArrayDeque<>();
    private int bufferedBytes;
    private long droppedBytes;
    private long truncationEvents;
    private int credits;

    public TerminalOutputPump() {
        this(DEFAULT_MAX_CHUNK_BYTES, DEFAULT_RING_CAPACITY, DEFAULT_CREDIT_WINDOW);
    }

    public TerminalOutputPump(int maxChunkBytes, int ringCapacity, int creditWindow) {
        this.maxChunkBytes = maxChunkBytes;
        this.ringCapacity = ringCapacity;
        this.creditWindow = creditWindow;
        this.credits = creditWindow;
    }

    /** Buffers PTY output, dropping the oldest bytes if the ring is over capacity. */
    public synchronized void append(byte[] data) {
        if (data.length == 0) {
            return;
        }
        ring.addLast(data);
        bufferedBytes += data.length;
        while (bufferedBytes > ringCapacity && !ring.isEmpty()) {
            byte[] evicted = ring.pollFirst();
            bufferedBytes -= evicted.length;
            droppedBytes += evicted.length;
            truncationEvents++;
        }
    }

    /**
     * Emits as many chunks as credits and buffered data allow. Returns the number of chunks
     * emitted. Call on a timer (~8 ms) and after {@link #ack}.
     */
    public synchronized int drain(ChunkSink sink) {
        int emitted = 0;
        while (credits > 0 && bufferedBytes > 0) {
            byte[] chunk = takeChunk();
            if (chunk.length == 0) {
                break;
            }
            sink.emit(chunk);
            credits--;
            emitted++;
        }
        return emitted;
    }

    /** The frontend consumed one chunk; replenish a credit (bounded by the window). */
    public synchronized void ack() {
        if (credits < creditWindow) {
            credits++;
        }
    }

    public synchronized int pendingBytes() {
        return bufferedBytes;
    }

    public synchronized long droppedBytes() {
        return droppedBytes;
    }

    public synchronized long truncationEvents() {
        return truncationEvents;
    }

    public synchronized int availableCredits() {
        return credits;
    }

    /** Coalesces buffered arrays into a single chunk up to {@code maxChunkBytes}. */
    private byte[] takeChunk() {
        int target = Math.min(bufferedBytes, maxChunkBytes);
        byte[] chunk = new byte[target];
        int filled = 0;
        while (filled < target && !ring.isEmpty()) {
            byte[] head = ring.peekFirst();
            int need = target - filled;
            if (head.length <= need) {
                System.arraycopy(head, 0, chunk, filled, head.length);
                filled += head.length;
                ring.pollFirst();
                bufferedBytes -= head.length;
            } else {
                System.arraycopy(head, 0, chunk, filled, need);
                byte[] remainder = new byte[head.length - need];
                System.arraycopy(head, need, remainder, 0, remainder.length);
                ring.pollFirst();
                ring.addFirst(remainder);
                bufferedBytes -= need;
                filled += need;
            }
        }
        return chunk;
    }
}
