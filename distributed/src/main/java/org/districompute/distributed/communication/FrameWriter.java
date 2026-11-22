package org.districompute.distributed.communication;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Writes newline-delimited frames. A frame must not contain a line feed, and it must fit the limit, so the peer can
 * always read it back.
 *
 * <p>Not thread-safe: one writer thread per connection.
 */
public final class FrameWriter {

    private final OutputStream out;
    private final int maxFrameBytes;
    private long bytesWritten;

    /**
     * Creates a writer.
     *
     * @param out the stream, typically a buffered socket output stream
     * @param maxFrameBytes largest frame the peer accepts
     */
    public FrameWriter(OutputStream out, int maxFrameBytes) {
        this.out = Objects.requireNonNull(out, "out");
        if (maxFrameBytes < 1) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        this.maxFrameBytes = maxFrameBytes;
    }

    /**
     * Validates a frame without writing it.
     *
     * @param frame the frame
     * @param maxFrameBytes the limit
     * @throws IllegalArgumentException if the frame is empty, too large or contains a line feed
     */
    public static void check(byte[] frame, int maxFrameBytes) {
        if (frame.length == 0) {
            throw new IllegalArgumentException("A frame must not be empty");
        }
        if (frame.length > maxFrameBytes) {
            throw new IllegalArgumentException("Frame of " + frame.length + " bytes exceeds the limit of "
                    + maxFrameBytes);
        }
        for (byte b : frame) {
            if (b == '\n') {
                throw new IllegalArgumentException("A frame must not contain a line feed");
            }
        }
    }

    /**
     * Writes one frame followed by a line feed, and flushes.
     *
     * @param frame the frame
     * @throws IOException if writing fails
     */
    public void write(byte[] frame) throws IOException {
        check(frame, maxFrameBytes);
        out.write(frame);
        out.write('\n');
        out.flush();
        bytesWritten += frame.length + 1L;
    }

    /**
     * Bytes written so far, including line feeds.
     *
     * @return the count
     */
    public long bytesWritten() {
        return bytesWritten;
    }
}
