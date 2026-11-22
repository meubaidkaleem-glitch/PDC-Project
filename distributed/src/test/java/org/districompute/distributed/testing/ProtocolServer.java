package org.districompute.distributed.testing;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.districompute.distributed.communication.FrameReader;
import org.districompute.distributed.protocol.Envelope;
import org.districompute.distributed.protocol.MessageCodec;
import org.districompute.distributed.protocol.MessageType;
import org.districompute.distributed.protocol.RegisterAckPayload;
import org.districompute.distributed.protocol.RegisterPayload;
import org.districompute.distributed.security.RegistrationAuthenticator;
import org.districompute.distributed.security.SharedSecret;

/**
 * A scripted coordinator-side protocol peer for testing workers in isolation: a real server socket and the real
 * codec, driven step by step by the test.
 */
public final class ProtocolServer implements AutoCloseable {

    private final ServerSocket server;
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile BlockingQueue<Envelope> received;
    private volatile CountDownLatch closed;
    private final java.util.List<Envelope> stash = new java.util.ArrayList<>();

    /**
     * Listens on an ephemeral loopback port.
     *
     * @throws IOException if binding fails
     */
    public ProtocolServer() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    }

    /**
     * The listening port.
     *
     * @return the port
     */
    public int port() {
        return server.getLocalPort();
    }

    /**
     * Accepts the next connection; earlier connections are forgotten.
     *
     * @param timeout how long to wait
     * @throws IOException if no connection arrives in time
     */
    public void accept(Duration timeout) throws IOException {
        server.setSoTimeout((int) timeout.toMillis());
        Socket accepted = server.accept();
        BlockingQueue<Envelope> queue = new LinkedBlockingQueue<>();
        CountDownLatch latch = new CountDownLatch(1);
        FrameReader reader = new FrameReader(accepted.getInputStream(), 64 << 20);
        Thread.ofVirtual().start(() -> {
            try {
                byte[] frame;
                while ((frame = reader.readFrame()) != null) {
                    queue.add(MessageCodec.decode(frame));
                }
            } catch (IOException | RuntimeException e) {
                // Closed by either side.
            } finally {
                latch.countDown();
            }
        });
        socket = accepted;
        stash.clear();
        out = accepted.getOutputStream();
        received = queue;
        closed = latch;
    }

    /**
     * Reads {@code REGISTER}, verifies it and answers with {@code REGISTER_ACK}.
     *
     * @param secret the secret the worker must know
     * @param provedSecret the secret used for the coordinator's own proof (a different one makes an impostor)
     * @param heartbeatIntervalMillis interval announced to the worker
     * @return the worker's {@code REGISTER}
     * @throws Exception if the proof is invalid
     */
    public Envelope acceptRegistration(SharedSecret secret, SharedSecret provedSecret, long heartbeatIntervalMillis)
            throws Exception {
        Envelope register = receive(MessageType.REGISTER, Duration.ofSeconds(5));
        RegisterPayload payload = register.payload(RegisterPayload.class);
        new RegistrationAuthenticator(secret).verify(register.protocolVersion(), register.workerId(),
                payload.proof());
        String session = UUID.randomUUID().toString();
        send(Envelope.builder(MessageType.REGISTER_ACK).inReplyTo(register.messageId()).workerId(register.workerId())
                .payload(new RegisterAckPayload(session, "scripted-coordinator", "test", heartbeatIntervalMillis,
                        heartbeatIntervalMillis * 20, 64 << 20, new RegistrationAuthenticator(provedSecret)
                                .coordinatorProof(register.workerId(), payload.proof(), session))));
        return register;
    }

    /**
     * Sends a message.
     *
     * @param message the message
     */
    public synchronized void send(Envelope message) {
        try {
            out.write(MessageCodec.encode(message));
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            throw new AssertionError("Sending failed: " + e.getMessage(), e);
        }
    }

    /**
     * Waits for the next message of a type, skipping others.
     *
     * @param type the type
     * @param timeout how long to wait in total
     * @return the message
     */
    public Envelope receive(MessageType type, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                long left = deadline - System.nanoTime();
                Envelope message = left <= 0 ? null : received.poll(left, TimeUnit.NANOSECONDS);
                if (message == null) {
                    throw new AssertionError("No " + type + " within " + timeout.toMillis() + " ms");
                }
                if (message.type() == type) {
                    return message;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted", e);
        }
    }

    /**
     * Waits for the next message that concerns an attempt and is not progress.
     *
     * @param attemptId the attempt
     * @param timeout how long to wait in total
     * @return {@code TASK_ACCEPTED}, {@code RESULT} or {@code ERROR}
     */
    public Envelope receiveFor(String attemptId, Duration timeout) {
        for (java.util.Iterator<Envelope> it = stash.iterator(); it.hasNext();) {
            Envelope stashed = it.next();
            if (attemptId.equals(stashed.attemptId())) {
                it.remove();
                return stashed;
            }
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                long left = deadline - System.nanoTime();
                Envelope message = left <= 0 ? null : received.poll(left, TimeUnit.NANOSECONDS);
                if (message == null) {
                    throw new AssertionError("No message for " + attemptId + " within " + timeout.toMillis() + " ms");
                }
                if (message.attemptId() == null || message.type() == MessageType.TASK_PROGRESS) {
                    continue;
                }
                if (attemptId.equals(message.attemptId())) {
                    return message;
                }
                stash.add(message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted", e);
        }
    }

    /**
     * Waits until the peer closes the current connection.
     *
     * @param timeout how long to wait
     * @return {@code true} if it closed in time
     */
    public boolean awaitClosed(Duration timeout) {
        try {
            return closed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Closes the current connection, as a coordinator crash would.
     *
     * @throws IOException if closing fails
     */
    public void dropConnection() throws IOException {
        socket.close();
    }

    @Override
    public void close() throws IOException {
        Socket current = socket;
        if (current != null) {
            current.close();
        }
        server.close();
    }
}
