package org.districompute.distributed.communication;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One framed TCP connection with a reader thread, a writer thread and a bounded outbound queue. Used by both the
 * coordinator (one per worker) and the worker (one to the coordinator).
 *
 * <ul>
 *   <li>{@link #send} never blocks: it enqueues the frame, and returns {@code false} if the connection is closed.
 *       If the queue is full, the peer is not reading, and the connection is closed with
 *       {@link CloseReason#OUTBOUND_OVERFLOW} instead of buffering without limit.</li>
 *   <li>Inbound frames are delivered to the {@link Listener} on the reader thread, in order.</li>
 *   <li>{@link Listener#onClosed} is called exactly once, whatever closed the connection.</li>
 * </ul>
 */
public final class Connection {

    private static final Logger LOG = LoggerFactory.getLogger(Connection.class);
    private static final Outbound CLOSE_MARKER = new Outbound(new byte[0], null);

    /** Receives inbound frames and the close notification. */
    public interface Listener {

        /**
         * A complete frame arrived. Called on the reader thread; must not block for long.
         *
         * @param connection the connection
         * @param frame the frame, without its line feed
         * @param receivedNanos {@link System#nanoTime()} when the frame was complete
         */
        void onFrame(Connection connection, byte[] frame, long receivedNanos);

        /**
         * A frame exceeded the inbound limit. The connection closes after the frames queued so far, so the
         * listener may still send a final error message.
         *
         * @param connection the connection
         * @param limit the limit in bytes
         */
        void onFrameTooLarge(Connection connection, int limit);

        /**
         * The connection is closed. Called once.
         *
         * @param connection the connection
         * @param reason why
         * @param cause the I/O failure, or {@code null}
         */
        void onClosed(Connection connection, CloseReason reason, Throwable cause);
    }

    private record Outbound(byte[] frame, LongConsumer onWritten) {
    }

    private final Socket socket;
    private final String name;
    private final Listener listener;
    private final int maxInboundBytes;
    private final BlockingQueue<Outbound> outbound;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicLong framesSent = new AtomicLong();
    private final AtomicLong framesReceived = new AtomicLong();
    private volatile int maxOutboundBytes;
    private volatile CloseReason pendingReason = CloseReason.LOCAL;
    private volatile FrameReader reader;
    private volatile FrameWriter writer;
    private volatile Thread writerThread;

    private Connection(Socket socket, String name, int maxInboundBytes, int maxOutboundBytes, int queueCapacity,
            Listener listener) {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.name = Objects.requireNonNull(name, "name");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.maxInboundBytes = maxInboundBytes;
        this.maxOutboundBytes = maxOutboundBytes;
        this.outbound = new ArrayBlockingQueue<>(queueCapacity);
    }

    /**
     * Wraps a connected socket. Call {@link #start()} once the connection has been handed to whoever needs it, so
     * that no frame can arrive before then.
     *
     * @param socket connected socket (plain or TLS)
     * @param name name used for threads and logs
     * @param maxInboundBytes largest frame accepted from the peer
     * @param maxOutboundBytes largest frame the peer accepts (can be raised or lowered later)
     * @param queueCapacity outbound queue capacity, in frames
     * @param listener receives frames and the close notification
     * @return the connection, not yet started
     * @throws IOException if the socket's streams cannot be opened
     */
    public static Connection open(Socket socket, String name, int maxInboundBytes, int maxOutboundBytes,
            int queueCapacity, Listener listener) throws IOException {
        Connection connection = new Connection(socket, name, maxInboundBytes, maxOutboundBytes, queueCapacity,
                listener);
        connection.reader = new FrameReader(socket.getInputStream(), maxInboundBytes);
        connection.writer = new FrameWriter(new BufferedOutputStream(socket.getOutputStream(), 64 * 1024),
                Integer.MAX_VALUE);
        return connection;
    }

    /**
     * Starts the reader and writer threads. Frames sent before this call are queued and written once started.
     */
    public void start() {
        if (writerThread != null) {
            throw new IllegalStateException(name + " is already started");
        }
        writerThread = Thread.ofVirtual().name(name + "-writer").start(this::writeLoop);
        Thread.ofVirtual().name(name + "-reader").start(this::readLoop);
    }

    /**
     * Enqueues a frame.
     *
     * @param frame the frame (no line feed)
     * @return {@code true} if queued, {@code false} if the connection is closed or closing
     * @throws IllegalArgumentException if the frame exceeds the peer's limit
     */
    public boolean send(byte[] frame) {
        return send(frame, null);
    }

    /**
     * Enqueues a frame and reports when it has been written to the socket.
     *
     * @param frame the frame (no line feed)
     * @param onWritten called on the writer thread with {@link System#nanoTime()} after the write, or
     *     {@code null}
     * @return {@code true} if queued, {@code false} if the connection is closed or closing
     * @throws IllegalArgumentException if the frame exceeds the peer's limit
     */
    public boolean send(byte[] frame, LongConsumer onWritten) {
        FrameWriter.check(frame, maxOutboundBytes);
        if (closing.get() || closed.get()) {
            return false;
        }
        if (!outbound.offer(new Outbound(frame, onWritten))) {
            LOG.warn("{}: outbound queue full ({} frames); the peer is not reading", name, outbound.size());
            close(CloseReason.OUTBOUND_OVERFLOW, null);
            return false;
        }
        return true;
    }

    /**
     * Closes the connection after the frames already queued have been written (for example after a final
     * {@code ERROR} or {@code SHUTDOWN}).
     *
     * @param reason reported to the listener
     */
    public void closeAfterFlush(CloseReason reason) {
        if (closing.compareAndSet(false, true)) {
            pendingReason = reason;
            if (!outbound.offer(CLOSE_MARKER)) {
                close(reason, null);
            }
        }
    }

    /**
     * Closes the connection immediately. Queued frames are discarded. Idempotent.
     *
     * @param reason reported to the listener
     */
    public void close(CloseReason reason) {
        close(reason, null);
    }

    /**
     * Sets the largest frame the peer accepts, once it is known.
     *
     * @param bytes the peer's limit
     */
    public void setMaxOutboundBytes(int bytes) {
        if (bytes < 1) {
            throw new IllegalArgumentException("bytes must be positive");
        }
        this.maxOutboundBytes = bytes;
    }

    /**
     * The largest frame the peer accepts.
     *
     * @return bytes
     */
    public int maxOutboundBytes() {
        return maxOutboundBytes;
    }

    /**
     * The largest frame accepted from the peer.
     *
     * @return bytes
     */
    public int maxInboundBytes() {
        return maxInboundBytes;
    }

    /**
     * Whether the connection is still open.
     *
     * @return {@code true} until it is closed
     */
    public boolean isOpen() {
        return !closed.get();
    }

    /**
     * The peer's address.
     *
     * @return host and port as text
     */
    public String remoteAddress() {
        SocketAddress address = socket.getRemoteSocketAddress();
        return address == null ? "unknown" : address.toString().replaceFirst("^/", "");
    }

    /**
     * Name of the connection.
     *
     * @return the name
     */
    public String name() {
        return name;
    }

    /**
     * Bytes received so far, including line feeds.
     *
     * @return the count
     */
    public long bytesReceived() {
        FrameReader r = reader;
        return r == null ? 0 : r.bytesRead();
    }

    /**
     * Bytes written so far, including line feeds.
     *
     * @return the count
     */
    public long bytesSent() {
        FrameWriter w = writer;
        return w == null ? 0 : w.bytesWritten();
    }

    /**
     * Frames written so far.
     *
     * @return the count
     */
    public long framesSent() {
        return framesSent.get();
    }

    /**
     * Frames received so far.
     *
     * @return the count
     */
    public long framesReceived() {
        return framesReceived.get();
    }

    @Override
    public String toString() {
        return name + "[" + remoteAddress() + "]";
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                byte[] frame = reader.readFrame();
                if (frame == null) {
                    close(CloseReason.PEER_CLOSED, null);
                    return;
                }
                framesReceived.incrementAndGet();
                listener.onFrame(this, frame, System.nanoTime());
            }
        } catch (FrameTooLargeException e) {
            listener.onFrameTooLarge(this, maxInboundBytes);
            closeAfterFlush(CloseReason.FRAME_TOO_LARGE);
        } catch (TruncatedFrameException e) {
            close(CloseReason.TRUNCATED_FRAME, e);
        } catch (IOException e) {
            close(closed.get() ? CloseReason.LOCAL : CloseReason.IO_ERROR, e);
        } catch (RuntimeException e) {
            LOG.error("{}: listener failed; closing the connection", name, e);
            close(CloseReason.LOCAL, e);
        }
    }

    private void writeLoop() {
        try {
            while (!closed.get()) {
                Outbound next = outbound.take();
                if (next == CLOSE_MARKER) {
                    close(pendingReason, null);
                    return;
                }
                writer.write(next.frame());
                framesSent.incrementAndGet();
                if (next.onWritten() != null) {
                    next.onWritten().accept(System.nanoTime());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            close(closed.get() ? CloseReason.LOCAL : CloseReason.IO_ERROR, e);
        } catch (RuntimeException e) {
            LOG.error("{}: write callback failed; closing the connection", name, e);
            close(CloseReason.LOCAL, e);
        }
    }

    private void close(CloseReason reason, Throwable cause) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closing.set(true);
        try {
            socket.close();
        } catch (IOException e) {
            LOG.debug("{}: error while closing the socket", name, e);
        }
        outbound.clear();
        Thread w = writerThread;
        if (w != null && w != Thread.currentThread()) {
            w.interrupt();
        }
        try {
            listener.onClosed(this, reason, cause);
        } catch (RuntimeException e) {
            LOG.error("{}: close listener failed", name, e);
        }
    }
}
