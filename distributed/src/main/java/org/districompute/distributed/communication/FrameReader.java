package org.districompute.distributed.communication;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;

/**
 * Reads newline-delimited frames from a byte stream. Partial reads are reassembled; a frame ends at a line feed
 * ({@code \n}), and a preceding carriage return is removed. Empty lines are skipped. A frame longer than the limit
 * is rejected as soon as the limit is passed, without buffering the rest.
 *
 * <p>Not thread-safe: one reader thread per connection.
 */
public final class FrameReader {

    private static final int CHUNK = 64 * 1024;

    private final InputStream in;
    private final int maxFrameBytes;
    private final byte[] chunk = new byte[CHUNK];
    private int chunkPos;
    private int chunkLimit;
    private byte[] frame = new byte[8 * 1024];
    private int frameLength;
    private long bytesRead;

    /**
     * Creates a reader.
     *
     * @param in the stream, typically a socket's input stream
     * @param maxFrameBytes largest accepted frame, excluding the line feed
     */
    public FrameReader(InputStream in, int maxFrameBytes) {
        this.in = Objects.requireNonNull(in, "in");
        if (maxFrameBytes < 1) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        this.maxFrameBytes = maxFrameBytes;
    }

    /**
     * Reads the next frame.
     *
     * @return the frame's bytes without the line feed, or {@code null} at a clean end of stream
     * @throws FrameTooLargeException if the frame exceeds the limit
     * @throws TruncatedFrameException if the stream ends inside a frame
     * @throws IOException if reading fails
     */
    public byte[] readFrame() throws IOException {
        while (true) {
            if (chunkPos == chunkLimit && !fill()) {
                if (frameLength > 0) {
                    int length = frameLength;
                    frameLength = 0;
                    throw new TruncatedFrameException(length);
                }
                return null;
            }
            int start = chunkPos;
            int newline = -1;
            for (int i = chunkPos; i < chunkLimit; i++) {
                if (chunk[i] == '\n') {
                    newline = i;
                    break;
                }
            }
            int end = newline < 0 ? chunkLimit : newline;
            append(start, end - start);
            chunkPos = newline < 0 ? chunkLimit : newline + 1;
            if (newline >= 0) {
                int length = frameLength;
                if (length > 0 && frame[length - 1] == '\r') {
                    length--;
                }
                frameLength = 0;
                if (length > maxFrameBytes) {
                    throw new FrameTooLargeException(maxFrameBytes);
                }
                if (length > 0) {
                    return Arrays.copyOf(frame, length);
                }
            }
        }
    }

    /**
     * Bytes read from the stream so far.
     *
     * @return the count
     */
    public long bytesRead() {
        return bytesRead;
    }

    private boolean fill() throws IOException {
        int n = in.read(chunk, 0, chunk.length);
        if (n < 0) {
            return false;
        }
        chunkPos = 0;
        chunkLimit = n;
        bytesRead += n;
        return true;
    }

    private void append(int offset, int length) throws FrameTooLargeException {
        if (length == 0) {
            return;
        }
        // One extra byte is allowed for a carriage return that is stripped later.
        if ((long) frameLength + length > (long) maxFrameBytes + 1) {
            frameLength = 0;
            throw new FrameTooLargeException(maxFrameBytes);
        }
        if (frameLength + length > frame.length) {
            int capacity = Math.max(frameLength + length, (int) Math.min((long) frame.length * 2, maxFrameBytes + 1L));
            frame = Arrays.copyOf(frame, capacity);
        }
        System.arraycopy(chunk, offset, frame, frameLength, length);
        frameLength += length;
    }
}
