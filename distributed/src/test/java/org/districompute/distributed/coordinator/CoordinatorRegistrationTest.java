package org.districompute.distributed.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.districompute.distributed.protocol.CancelPayload;
import org.districompute.distributed.protocol.CancelReason;
import org.districompute.distributed.protocol.Envelope;
import org.districompute.distributed.protocol.ErrorPayload;
import org.districompute.distributed.protocol.HeartbeatAckPayload;
import org.districompute.distributed.protocol.HeartbeatPayload;
import org.districompute.distributed.protocol.MessageType;
import org.districompute.distributed.protocol.ProtocolErrorCode;
import org.districompute.distributed.protocol.RegisterAckPayload;
import org.districompute.distributed.protocol.ShutdownPayload;
import org.districompute.distributed.protocol.TaskAcceptedPayload;
import org.districompute.distributed.protocol.AttemptRef;
import org.districompute.distributed.protocol.WorkerCapabilities;
import org.districompute.distributed.registry.WorkerSnapshot;
import org.districompute.distributed.registry.WorkerStatus;
import org.districompute.distributed.security.RegistrationAuthenticator;
import org.districompute.distributed.security.SharedSecret;
import org.districompute.distributed.testing.Await;
import org.districompute.distributed.testing.CoreFixtures;
import org.districompute.distributed.testing.ProtocolClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Registration, health tracking and protocol enforcement over real loopback TCP connections. */
@Tag("integration")
class CoordinatorRegistrationTest {

    static final SharedSecret SECRET = SharedSecret.of("integration-test-secret");
    private static final Duration WAIT = Duration.ofSeconds(5);

    private Coordinator coordinator;

    @AfterEach
    void stop() {
        if (coordinator != null) {
            coordinator.close();
        }
    }

    @Test
    void aWorkerRegistersAndIsListedWithItsCapabilities() throws IOException {
        coordinator = Coordinator.start(config().build());
        WorkerCapabilities capabilities = CoreFixtures.capabilities(3);
        try (ProtocolClient client = ProtocolClient.connect(coordinator.port())) {
            Envelope ack = client.register("w1", SECRET, capabilities);
            assertEquals(MessageType.REGISTER_ACK, ack.type());
            RegisterAckPayload payload = ack.payload(RegisterAckPayload.class);
            assertTrue(new RegistrationAuthenticator(SECRET).verifyCoordinator("w1", client.lastProof(),
                    payload.sessionId(), payload.coordinatorProof()), "the coordinator proves the secret");
            assertEquals(200, payload.heartbeatIntervalMillis());

            WorkerSnapshot worker = coordinator.worker("w1").orElseThrow();
            assertEquals(WorkerStatus.ACTIVE, worker.status());
            assertEquals(payload.sessionId(), worker.sessionId());
            assertEquals(capabilities, worker.capabilities());
            assertEquals(3, worker.maxConcurrentTasks());
            assertEquals(3, worker.freeSlots());
            assertEquals(1, worker.registrations());
            assertTrue(worker.endpoint().startsWith("127.0.0.1:"), worker.endpoint());
        }
    }

    @Test
    void aWrongSecretIsRejectedAndTheConnectionClosed() throws IOException {
        coordinator = Coordinator.start(config().build());
        try (ProtocolClient client = ProtocolClient.connect(coordinator.port())) {
            Envelope reply = client.register("w1", SharedSecret.of("not-the-cluster-secret"),
                    CoreFixtures.capabilities(1));
            assertError(reply, ProtocolErrorCode.AUTHENTICATION_FAILED, true);
            assertTrue(client.awaitClosed(WAIT));
        }
        assertTrue(coordinator.workers().isEmpty());
    }

    @Test
    void messagesBeforeRegistrationAreRefused() throws IOException {
        coordinator = Coordinator.start(config().build());
        try (ProtocolClient client = ProtocolClient.connect(coordinator.port())) {
            client.send(heartbeat("w1", 1, 1));
            assertError(client.receive(WAIT), ProtocolErrorCode.NOT_REGISTERED, true);
            assertTrue(client.awaitClosed(WAIT));
        }
    }

    @Test
    void aSecondConnectionWithTheSameWorkerIdIsRejected() throws IOException {
        coordinator = Coordinator.start(config().build());
        try (ProtocolClient first = ProtocolClient.connect(coordinator.port());
                ProtocolClient second = ProtocolClient.connect(coordinator.port())) {
            assertEquals(MessageType.REGISTER_ACK, first.register("w1", SECRET, CoreFixtures.capabilities(1)).type());
            Envelope reply = second.register("w1", SECRET, CoreFixtures.capabilities(1));
            assertError(reply, ProtocolErrorCode.REGISTRATION_REJECTED, true);
            assertTrue(second.awaitClosed(WAIT));
            assertEquals(WorkerStatus.ACTIVE, coordinator.worker("w1").orElseThrow().status());
        }
    }

    @Test
    void aReconnectingWorkerKeepsItsRecordAndGetsANewSession() throws IOException {
        coordinator = Coordinator.start(config().build());
        String firstSession;
        try (ProtocolClient client = ProtocolClient.connect(coordinator.port())) {
            firstSession = client.register("w1", SECRET, CoreFixtures.capabilities(1))
                    .payload(RegisterAckPayload.class).sessionId();
        }
        Await.until(WAIT, () -> coordinator.worker("w1").orElseThrow().status() == WorkerStatus.DISCONNECTED,
                "the worker to be marked disconnected");
        assertTrue(coordinator.worker("w1").orElseThrow().statusReason().contains("connection closed"));
        try (ProtocolClient client = ProtocolClient.connect(coordinator.port())) {
            String secondSession = client.register("w1", SECRET, CoreFixtures.capabilities(2))
                    .payload(RegisterAckPayload.class).sessionId();
            assertNotEquals(firstSession, secondSession);
            WorkerSnapshot worker = coordinator.worker("w1").orElseThrow();
            assertEquals(2, worker.registrations());
            assertEquals(WorkerStatus.ACTIVE, worker.status());
            assertEquals(2, worker.maxConcurrentTasks());
        }
    }

    @Test
    void heartbeatsAreAcknowledgedAndReportLoadAndCapacity() throws IOException {
        coordinator = Coordinator.start(config().build());
        try (ProtocolClient client = ProtocolClient.connect(coordinator.port())) {
            client.register("w1", SECRET, CoreFixtures.capabilities(4));
            Envelope beat = heartbeat("w1", 1, 2);
            client.send(beat);
            Envelope ack = client.receive(MessageType.HEARTBEAT_ACK, WAIT);
            assertEquals(beat.messageId(), ack.inReplyTo());
            assertEquals(new HeartbeatAckPayload(1, "ACTIVE"), ack.payload(HeartbeatAckPayload.class));
            WorkerSnapshot worker = coordinator.worker("w1").orElseThrow();
            assertEquals(2, worker.maxConcurrentTasks(), "capacity follows the latest heartbeat");
            assertEquals(1, worker.load().activeTasks());
            assertEquals(123_456, worker.load().freeMemoryBytes());
        }
    }

    @Test
    void repeatedMessageIdsAreProcessedOnce() throws IOException {
        coordinator = Coordinator.start(config().build());
        try (ProtocolClient client = ProtocolClient.connect(coordinator.port())) {
            client.register("w1", SECRET, CoreFixtures.capabilities(4));
            Envelope beat = heartbeat("w1", 1, 4);
            client.send(beat);
            client.send(beat);
            client.send(heartbeat("w1", 2, 4));
            assertEquals(1, client.receive(MessageType.HEARTBEAT_ACK, WAIT).payload(HeartbeatAckPayload.class)
                    .sequence());
            assertEquals(2, client.receive(MessageType.HEARTBEAT_ACK, WAIT).payload(HeartbeatAckPayload.class)
                    .sequence(), "the repeated heartbeat was dropped");
        }
    }

    @Test
    void aSilentWorkerBecomesSuspectThenIsDisconnected() throws IOException {
        coordinator = Coordinator.start(config().build());
        try (ProtocolClient client = ProtocolClient.connect(coordinator.port())) {
            client.register("w1", SECRET, CoreFixtures.capabilities(1));
            Await.until(WAIT, () -> coordinator.worker("w1").orElseThrow().status() == WorkerStatus.SUSPECT,
                    "the worker to become suspect");
            client.send(heartbeat("w1", 1, 1));
            Await.until(WAIT, () -> coordinator.worker("w1").orElseThrow().status() == WorkerStatus.ACTIVE,
                    "a heartbeat to restore the worker");
            Await.until(WAIT, () -> coordinator.worker("w1").orElseThrow().status() == WorkerStatus.DISCONNECTED,
                    "the heartbeat timeout");
            assertTrue(coordinator.worker("w1").orElseThrow().statusReason().contains("heartbeat timeout"));
            assertTrue(client.awaitClosed(WAIT), "the coordinator closes the connection");
        }
    }

    @Test
    void malformedMessagesAreReportedAndRepeatedViolationsCloseTheConnection() throws IOException {
        coordinator = Coordinator.start(config().maxProtocolErrors(3).build());
        try (ProtocolClient client = ProtocolClient.connect(coordinator.port())) {
            client.register("w1", SECRET, CoreFixtures.capabilities(1));
            client.sendRaw("{this is not json");
            assertError(client.receive(MessageType.ERROR, WAIT), ProtocolErrorCode.MALFORMED_MESSAGE, false);
            client.sendRaw("{\"protocolVersion\":\"0.1\",\"type\":\"WARP\",\"messageId\":\"m1\"}");
            assertError(client.receive(MessageType.ERROR, WAIT), ProtocolErrorCode.UNKNOWN_MESSAGE_TYPE, false);
            client.send(heartbeat("w1", 1, 1));
            assertEquals(MessageType.HEARTBEAT_ACK, client.receive(MessageType.HEARTBEAT_ACK, WAIT).type(),
                    "the connection still works after non-fatal errors");
            client.sendRaw("[]");
            assertError(client.receive(MessageType.ERROR, WAIT), ProtocolErrorCode.MALFORMED_MESSAGE, true);
            assertTrue(client.awaitClosed(WAIT));
        }
    }

    @Test
    void oversizedFramesCloseTheConnection() throws IOException {
        coordinator = Coordinator.start(config().maxMessageBytes(4096).build());
        try (ProtocolClient client = ProtocolClient.connect(coordinator.port())) {
            client.sendRaw("x".repeat(10_000));
            assertError(client.receive(WAIT), ProtocolErrorCode.MESSAGE_TOO_LARGE, true);
            assertTrue(client.awaitClosed(WAIT));
        }
    }

    @Test
    void anUnsupportedProtocolVersionClosesTheConnection() throws IOException {
        coordinator = Coordinator.start(config().build());
        try (ProtocolClient client = ProtocolClient.connect(coordinator.port())) {
            client.sendRaw("{\"protocolVersion\":\"2.0\",\"type\":\"REGISTER\",\"messageId\":\"m-9\","
                    + "\"payload\":{}}");
            Envelope reply = client.receive(WAIT);
            assertError(reply, ProtocolErrorCode.UNSUPPORTED_PROTOCOL_VERSION, true);
            assertEquals("m-9", reply.inReplyTo());
            assertTrue(client.awaitClosed(WAIT));
        }
    }

    @Test
    void connectionsThatNeverRegisterAreClosed() throws IOException {
        coordinator = Coordinator.start(config().registrationTimeout(Duration.ofMillis(300)).build());
        try (ProtocolClient client = ProtocolClient.connect(coordinator.port())) {
            assertError(client.receive(WAIT), ProtocolErrorCode.NOT_REGISTERED, true);
            assertTrue(client.awaitClosed(WAIT));
        }
    }

    @Test
    void faultSimulationWorkersNeedAnExplicitOptIn() throws IOException {
        WorkerCapabilities faulty = withFaultSimulation(CoreFixtures.capabilities(1));
        coordinator = Coordinator.start(config().build());
        try (ProtocolClient client = ProtocolClient.connect(coordinator.port())) {
            assertError(client.register("w1", SECRET, faulty), ProtocolErrorCode.REGISTRATION_REJECTED, true);
        }
        coordinator.close();
        coordinator = Coordinator.start(config().allowFaultSimulationWorkers(true).build());
        try (ProtocolClient client = ProtocolClient.connect(coordinator.port())) {
            assertEquals(MessageType.REGISTER_ACK, client.register("w1", SECRET, faulty).type());
            assertTrue(coordinator.worker("w1").orElseThrow().capabilities().faultSimulation());
        }
    }

    @Test
    void theWorkerLimitIsEnforced() throws IOException {
        coordinator = Coordinator.start(config().maxWorkers(1).build());
        try (ProtocolClient first = ProtocolClient.connect(coordinator.port());
                ProtocolClient second = ProtocolClient.connect(coordinator.port())) {
            assertEquals(MessageType.REGISTER_ACK, first.register("w1", SECRET, CoreFixtures.capabilities(1)).type());
            assertError(second.register("w2", SECRET, CoreFixtures.capabilities(1)),
                    ProtocolErrorCode.REGISTRATION_REJECTED, true);
        }
    }

    @Test
    void unexpectedForeignAndUnknownMessagesAreRejected() throws IOException {
        coordinator = Coordinator.start(config().build());
        AttemptRef ref = new AttemptRef(null, "t-1", "t-1/p0", "t-1/p0/a1");
        try (ProtocolClient client = ProtocolClient.connect(coordinator.port())) {
            client.register("w1", SECRET, CoreFixtures.capabilities(1));
            client.send(Envelope.builder(MessageType.TASK_ACCEPTED).workerId("w1").attempt(ref)
                    .payload(new TaskAcceptedPayload(1, 1)));
            assertError(client.receive(MessageType.ERROR, WAIT), ProtocolErrorCode.UNKNOWN_TASK, false);
            client.send(Envelope.builder(MessageType.CANCEL).workerId("w1").attempt(ref)
                    .payload(new CancelPayload(CancelReason.USER_REQUESTED, null)));
            assertError(client.receive(MessageType.ERROR, WAIT), ProtocolErrorCode.UNEXPECTED_MESSAGE, false);
            client.send(heartbeat("w2", 1, 1));
            assertError(client.receive(MessageType.ERROR, WAIT), ProtocolErrorCode.INVALID_MESSAGE, true);
            assertTrue(client.awaitClosed(WAIT), "impersonating another worker closes the connection");
        }
    }

    @Test
    void shutdownTellsWorkersToReconnectLater() throws IOException {
        coordinator = Coordinator.start(config().build());
        try (ProtocolClient client = ProtocolClient.connect(coordinator.port())) {
            client.register("w1", SECRET, CoreFixtures.capabilities(1));
            coordinator.close();
            ShutdownPayload shutdown = client.receive(MessageType.SHUTDOWN, WAIT).payload(ShutdownPayload.class);
            assertTrue(shutdown.reconnect());
            assertFalse(shutdown.drain());
            assertTrue(client.awaitClosed(WAIT));
        }
    }

    static CoordinatorConfig.Builder config() {
        return CoordinatorConfig.builder(SECRET).coordinatorId("test-coordinator").bindAddress("127.0.0.1").port(0)
                .heartbeat(Duration.ofMillis(200), Duration.ofMillis(400), Duration.ofMillis(1200))
                .tickInterval(Duration.ofMillis(20));
    }

    static Envelope heartbeat(String workerId, long sequence, int capacity) {
        return Envelope.builder(MessageType.HEARTBEAT).workerId(workerId)
                .payload(new HeartbeatPayload(sequence, 1, capacity, 123_456, 0.1, 0.05, 0, 0, List.of()));
    }

    private static WorkerCapabilities withFaultSimulation(WorkerCapabilities c) {
        return new WorkerCapabilities(c.schemaVersion(), c.hostname(), c.osName(), c.osArch(), c.javaVersion(),
                c.runtimeVersion(), c.coreVersion(), c.cpuCores(), c.maxHeapBytes(), c.totalMemoryBytes(),
                c.maxConcurrentTasks(), c.maxTaskMemoryBytes(), c.maxMessageBytes(), c.algorithms(), c.gpu(),
                c.labels(), true);
    }

    private static void assertError(Envelope message, ProtocolErrorCode code, boolean fatal) {
        assertEquals(MessageType.ERROR, message.type(), message.toString());
        ErrorPayload error = message.payload(ErrorPayload.class);
        assertEquals(code, error.code(), error.message());
        assertEquals(fatal, error.fatal(), error.message());
    }
}
