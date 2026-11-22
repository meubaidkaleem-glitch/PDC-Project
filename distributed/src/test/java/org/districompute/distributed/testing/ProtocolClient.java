package org.districompute.distributed.testing;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.districompute.distributed.communication.FrameReader;
import org.districompute.distributed.communication.Sockets;
import org.districompute.distributed.communication.TlsConfig;
import org.districompute.distributed.protocol.Envelope;
import org.districompute.distributed.protocol.MessageCodec;
import org.districompute.distributed.protocol.MessageType;
import org.districompute.distributed.protocol.ProtocolVersion;
import org.districompute.distributed.protocol.RegisterPayload;
import org.districompute.distributed.protocol.RegistrationProof;
import org.districompute.distributed.protocol.WorkerCapabilities;
import org.districompute.distributed.security.RegistrationAuthenticator;
import org.districompute.distributed.security.SharedSecret;

/**
 * A minimal protocol peer for tests: a real TCP socket and the real codec, with full control over what is sent, so
 * tests can also send malformed or malicious input.
 */
public final class ProtocolClient implements AutoCloseable {

    private final Socket socket;
    private final OutputStream out;
    private final BlockingQueue<Envelope> received = new LinkedBlockingQueue<>();
    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile RegistrationProof lastProof;

    private ProtocolClient(Socket socket) throws IOException {
        this.socket = socket;
        this.out = socket.getOutputStream();
        FrameReader reader = new FrameReader(socket.getInputStream(), 64 << 20);
        Thread.ofVirtual().start(() -> {
            try {
                byte[] frame;
                while ((frame = reader.readFrame()) != null) {
                    received.add(MessageCodec.decode(frame));
                }
            } catch (IOException | RuntimeException e) {
                // The connection ended or the coordinator sent something undecodable; either way it is closed.
            } finally {
                closed.countDown();
            }
        });
    }

    /**
     * Connects to a coordinator on this machine.
     *
     * @param port the coordinator's port
     * @return the client
     * @throws IOException if the connection fails
     */
    public static ProtocolClient connect(int port) throws IOException {
        return connect(TlsConfig.DISABLED, "127.0.0.1", port);
    }

    /**
     * Connects to a coordinator.
     *
     * @param tls TLS settings
     * @param host host
     * @param port port
     * @return the client
     * @throws IOException if the connection fails
     */
    public static ProtocolClient connect(TlsConfig tls, String host, int port) throws IOException {
        return new ProtocolClient(Sockets.connect(tls, host, port, 5_000));
    }

    /**
     * Sends {@code REGISTER} and waits for the answer.
     *
     * @param workerId worker id
     * @param secret secret used for the proof
     * @param capabilities capabilities to report
     * @return {@code REGISTER_ACK} or {@code ERROR}
     */
    public Envelope register(String workerId, SharedSecret secret, WorkerCapabilities capabilities) {
        lastProof = new RegistrationAuthenticator(secret).prove(ProtocolVersion.CURRENT, workerId);
        send(Envelope.builder(MessageType.REGISTER).workerId(workerId)
                .payload(new RegisterPayload(capabilities, lastProof)));
        return receive(Duration.ofSeconds(5));
    }

    /**
     * The proof sent by the last {@link #register} call.
     *
     * @return the proof
     */
    public RegistrationProof lastProof() {
        return lastProof;
    }

    /**
     * Sends a message.
     *
     * @param message the message
     */
    public void send(Envelope message) {
        sendBytes(MessageCodec.encode(message));
    }

    /**
     * Sends a raw line (a line feed is appended).
     *
     * @param line the line
     */
    public void sendRaw(String line) {
        sendBytes(line.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Sends raw bytes followed by a line feed.
     *
     * @param bytes the bytes
     */
    public synchronized void sendBytes(byte[] bytes) {
        try {
            out.write(bytes);
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            throw new AssertionError("Sending failed: " + e.getMessage(), e);
        }
    }

    /**
     * Waits for the next message.
     *
     * @param timeout how long to wait
     * @return the message
     * @throws AssertionError if none arrives in time
     */
    public Envelope receive(Duration timeout) {
        try {
            Envelope message = received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (message == null) {
                throw new AssertionError("No message within " + timeout.toMillis() + " ms");
            }
            return message;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted", e);
        }
    }

    /**
     * Waits for the next message of a type, skipping others (for example heartbeat acknowledgements).
     *
     * @param type the type
     * @param timeout how long to wait in total
     * @return the message
     */
    public Envelope receive(MessageType type, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new AssertionError("No " + type + " within " + timeout.toMillis() + " ms");
            }
            Envelope message = receive(Duration.ofNanos(left));
            if (message.type() == type) {
                return message;
            }
        }
    }

    /**
     * Waits until the coordinator closes the connection.
     *
     * @param timeout how long to wait
     * @return {@code true} if it was closed in time
     */
    public boolean awaitClosed(Duration timeout) {
        try {
            return closed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
