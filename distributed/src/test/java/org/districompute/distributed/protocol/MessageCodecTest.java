package org.districompute.distributed.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.districompute.core.ErrorCode;
import org.districompute.core.ErrorInfo;
import org.districompute.core.partition.PartialResultMessage;
import org.districompute.core.partition.PartitionedTaskSession;
import org.districompute.core.partition.WorkUnitMessage;
import org.districompute.core.task.TaskPriority;
import org.districompute.core.task.TaskStatus;
import org.districompute.distributed.security.RegistrationAuthenticator;
import org.districompute.distributed.security.SharedSecret;
import org.districompute.distributed.testing.CoreFixtures;
import org.junit.jupiter.api.Test;

class MessageCodecTest {

    private static final String WORKER = "worker-1";

    private final PartitionedTaskSession session = CoreFixtures.session(CoreFixtures.primesTask("t-1", 200_000, 4));
    private final WorkUnitMessage unit = session.workUnit(2, 1);
    private final AttemptRef ref = new AttemptRef("exp-test", "t-1", "t-1/p2", "t-1/p2/a1");

    @Test
    void everyMessageTypeRoundTripsThroughOneLineOfJson() {
        Set<MessageType> covered = EnumSet.noneOf(MessageType.class);
        for (Envelope original : samples()) {
            byte[] frame = MessageCodec.encode(original);
            for (byte b : frame) {
                assertTrue(b != '\n', "frames must not contain a line feed");
            }
            Envelope decoded = MessageCodec.decode(frame);
            assertEquals(original, decoded, original.type().name());
            assertArrayEquals(frame, MessageCodec.encode(decoded), "re-encoding is stable");
            covered.add(original.type());
        }
        assertEquals(EnumSet.allOf(MessageType.class), covered);
    }

    @Test
    void coreWorkUnitAndPartialResultSurviveTheProtocolUnchanged() {
        PartialResultMessage partial = CoreFixtures.execute(unit, WORKER);
        Envelope result = Envelope.builder(MessageType.RESULT).workerId(WORKER).attempt(ref)
                .payload(new ResultPayload(partial, new WorkerTiming(10, 2000, 3000)));
        Envelope decoded = MessageCodec.decode(MessageCodec.encode(result));
        // Core accepts the decoded result exactly as if it had never left the process.
        assertTrue(session.accept(decoded.payload(ResultPayload.class).partialResult()).accepted());
    }

    @Test
    void malformedInputIsClassified() {
        assertCode(ProtocolErrorCode.MALFORMED_MESSAGE, "{not json");
        assertCode(ProtocolErrorCode.MALFORMED_MESSAGE, "[1,2,3]");
        assertCode(ProtocolErrorCode.MALFORMED_MESSAGE, "{\"a\":1} trailing");
        assertCode(ProtocolErrorCode.UNSUPPORTED_PROTOCOL_VERSION, json("\"1.0\"", "\"HEARTBEAT_ACK\""));
        assertCode(ProtocolErrorCode.UNSUPPORTED_PROTOCOL_VERSION, json("\"0.2\"", "\"HEARTBEAT_ACK\""));
        assertCode(ProtocolErrorCode.UNSUPPORTED_PROTOCOL_VERSION, "{\"type\":\"HEARTBEAT_ACK\"}");
        assertCode(ProtocolErrorCode.UNKNOWN_MESSAGE_TYPE, json("\"0.1\"", "\"TELEPORT\""));
        assertCode(ProtocolErrorCode.INVALID_MESSAGE, json("\"0.1\"", "7"));
        assertCode(ProtocolErrorCode.INVALID_MESSAGE, "{\"protocolVersion\":\"0.1\",\"type\":\"HEARTBEAT_ACK\","
                + "\"messageId\":\"m1\",\"timestamp\":\"2026-11-22T10:00:00Z\"}");
        assertCode(ProtocolErrorCode.INVALID_MESSAGE, "{\"protocolVersion\":\"0.1\",\"type\":\"HEARTBEAT_ACK\","
                + "\"messageId\":\"m1\",\"timestamp\":\"yesterday\",\"payload\":{\"sequence\":1,"
                + "\"workerStatus\":\"ACTIVE\"}}");
        assertCode(ProtocolErrorCode.INVALID_PAYLOAD, "{\"protocolVersion\":\"0.1\",\"type\":\"HEARTBEAT_ACK\","
                + "\"messageId\":\"m1\",\"timestamp\":\"2026-11-22T10:00:00Z\",\"payload\":{\"sequence\":0}}");
    }

    @Test
    void theOffendingMessageIdIsReportedWhenReadable() {
        ProtocolException e = assertThrows(ProtocolException.class, () -> MessageCodec.decode(bytes(
                "{\"protocolVersion\":\"0.1\",\"type\":\"TELEPORT\",\"messageId\":\"m-42\"}")));
        assertEquals("m-42", e.messageId());
        assertFalse(e.fatal());
        ProtocolException version = assertThrows(ProtocolException.class,
                () -> MessageCodec.decode(bytes(json("\"9.0\"", "\"HEARTBEAT\""))));
        assertTrue(version.fatal(), "a wrong protocol version closes the connection");
    }

    @Test
    void attemptIdsAreRequiredAndMustBeConsistent() {
        TaskPayload payload = new TaskPayload(unit, 60_000, TaskPriority.HIGH);
        assertInvalid(() -> Envelope.builder(MessageType.TASK).workerId(WORKER).payload(payload));
        assertInvalid(() -> Envelope.builder(MessageType.TASK).workerId(WORKER)
                .attempt(new AttemptRef(null, "t-1", "t-2/p2", "t-2/p2/a1")).payload(payload));
        assertInvalid(() -> Envelope.builder(MessageType.TASK).workerId(WORKER)
                .attempt(new AttemptRef(null, "t-1", "t-1/p2", "t-1/p2/b1")).payload(payload));
        // The work unit inside the payload must be the one the ids name.
        assertInvalid(() -> Envelope.builder(MessageType.TASK).workerId(WORKER)
                .attempt(new AttemptRef(null, "t-1", "t-1/p3", "t-1/p3/a1")).payload(payload));
        assertInvalid(() -> Envelope.builder(MessageType.TASK).workerId(WORKER)
                .attempt(new AttemptRef(null, "t-1", "t-1/p2", "t-1/p2/a2")).payload(payload));
        // Connection messages carry no task ids.
        assertInvalid(() -> Envelope.builder(MessageType.HEARTBEAT_ACK).attempt(ref)
                .payload(new HeartbeatAckPayload(1, "ACTIVE")));
    }

    @Test
    void aResultMustComeFromTheWorkerItNames() {
        PartialResultMessage partial = CoreFixtures.execute(unit, "worker-other");
        assertInvalid(() -> Envelope.builder(MessageType.RESULT).workerId(WORKER).attempt(ref)
                .payload(new ResultPayload(partial, new WorkerTiming(0, 0, 0))));
    }

    @Test
    void payloadClassMustMatchTheType() {
        ProtocolException e = assertThrows(ProtocolException.class, () -> Envelope.builder(MessageType.HEARTBEAT)
                .workerId(WORKER).payload(new HeartbeatAckPayload(1, "ACTIVE")));
        assertEquals(ProtocolErrorCode.INVALID_PAYLOAD, e.code());
    }

    @Test
    void unknownFieldsAndUnknownEnumValuesAreTolerated() {
        String text = "{\"protocolVersion\":\"0.1\",\"type\":\"ERROR\",\"messageId\":\"m1\","
                + "\"timestamp\":\"2026-11-22T10:00:00Z\",\"futureField\":{\"x\":1},"
                + "\"payload\":{\"code\":\"SOMETHING_NEW\",\"message\":\"hello\",\"retryable\":true,"
                + "\"fatal\":false,\"extra\":[1]}}";
        ErrorPayload error = MessageCodec.decode(bytes(text)).payload(ErrorPayload.class);
        assertEquals(ProtocolErrorCode.UNKNOWN, error.code());
        assertEquals("hello", error.message());
    }

    @Test
    void errorMessagesAreBounded() {
        ErrorPayload error = new ErrorPayload(ProtocolErrorCode.INTERNAL_ERROR, "x".repeat(10_000), false, false,
                null);
        assertEquals(ErrorPayload.MAX_MESSAGE_LENGTH, error.message().length());
        assertEquals("TASK_REJECTED", new ErrorPayload(ProtocolErrorCode.TASK_REJECTED, " ", true, false, null)
                .message());
    }

    @Test
    void directionsAreDeclaredPerType() {
        assertTrue(MessageType.REGISTER.canBeSentBy(MessageType.Role.WORKER));
        assertFalse(MessageType.REGISTER.canBeSentBy(MessageType.Role.COORDINATOR));
        assertTrue(MessageType.TASK.canBeSentBy(MessageType.Role.COORDINATOR));
        assertFalse(MessageType.TASK.canBeSentBy(MessageType.Role.WORKER));
        assertTrue(MessageType.ERROR.canBeSentBy(MessageType.Role.WORKER));
        assertTrue(MessageType.SHUTDOWN.canBeSentBy(MessageType.Role.COORDINATOR));
    }

    @Test
    void nullOptionalFieldsAreOmitted() {
        String text = new String(MessageCodec.encode(Envelope.builder(MessageType.HEARTBEAT_ACK)
                .payload(new HeartbeatAckPayload(3, "ACTIVE"))), StandardCharsets.UTF_8);
        assertFalse(text.contains("null"), text);
        assertFalse(text.contains("taskId"), text);
        assertNull(MessageCodec.decode(bytes(text)).attemptRef());
    }

    private List<Envelope> samples() {
        RegistrationAuthenticator auth = new RegistrationAuthenticator(SharedSecret.of("0123456789abcdef-secret"));
        RegistrationProof proof = auth.prove(ProtocolVersion.CURRENT, WORKER);
        PartialResultMessage partial = CoreFixtures.execute(unit, WORKER);
        Instant now = Instant.parse("2026-11-22T10:15:30.123Z");
        return List.of(
                Envelope.builder(MessageType.REGISTER).timestamp(now).workerId(WORKER)
                        .payload(new RegisterPayload(CoreFixtures.capabilities(4), proof)),
                Envelope.builder(MessageType.REGISTER_ACK).inReplyTo("m-1").workerId(WORKER)
                        .payload(new RegisterAckPayload("s-1", "coordinator-1", "0.1.0", 1000, 5000, 1 << 20,
                                auth.coordinatorProof(WORKER, proof, "s-1"))),
                Envelope.builder(MessageType.TASK).workerId(WORKER).attempt(ref)
                        .payload(new TaskPayload(unit, 60_000, TaskPriority.HIGH)),
                Envelope.builder(MessageType.TASK_ACCEPTED).workerId(WORKER).attempt(ref)
                        .payload(new TaskAcceptedPayload(1, 4)),
                Envelope.builder(MessageType.TASK_PROGRESS).workerId(WORKER).attempt(ref)
                        .payload(new TaskProgressPayload(500, 1000, "numbers")),
                Envelope.builder(MessageType.RESULT).workerId(WORKER).attempt(ref)
                        .payload(new ResultPayload(partial, new WorkerTiming(5, 1_000_000, 1_200_000))),
                Envelope.builder(MessageType.ERROR).workerId(WORKER).attempt(ref).inReplyTo("m-7")
                        .payload(new ErrorPayload(ProtocolErrorCode.TASK_FAILED, "kernel failed", true, false,
                                new ErrorInfo(ErrorCode.PARTITION_FAILED, "boom", true, TaskStatus.RUNNING, 2,
                                        List.of()))),
                Envelope.builder(MessageType.ERROR)
                        .payload(new ErrorPayload(ProtocolErrorCode.NOT_REGISTERED, null, false, true, null)),
                Envelope.builder(MessageType.HEARTBEAT).workerId(WORKER)
                        .payload(new HeartbeatPayload(7, 1, 4, 123_456_789, 0.5, 0.25, 10, 1,
                                List.of(ref.attemptId()))),
                Envelope.builder(MessageType.HEARTBEAT_ACK).workerId(WORKER)
                        .payload(new HeartbeatAckPayload(7, "ACTIVE")),
                Envelope.builder(MessageType.CANCEL).workerId(WORKER).attempt(ref)
                        .payload(new CancelPayload(CancelReason.TIMEOUT, "attempt exceeded 60000 ms")),
                Envelope.builder(MessageType.SHUTDOWN).workerId(WORKER)
                        .payload(new ShutdownPayload("maintenance", true, false)));
    }

    private static void assertCode(ProtocolErrorCode expected, String text) {
        ProtocolException e = assertThrows(ProtocolException.class, () -> MessageCodec.decode(bytes(text)), text);
        assertEquals(expected, e.code(), e.getMessage());
    }

    private static void assertInvalid(org.junit.jupiter.api.function.Executable action) {
        ProtocolException e = assertThrows(ProtocolException.class, action);
        assertEquals(ProtocolErrorCode.INVALID_MESSAGE, e.code(), e.getMessage());
    }

    private static String json(String version, String type) {
        return "{\"protocolVersion\":" + version + ",\"type\":" + type + ",\"messageId\":\"m1\","
                + "\"timestamp\":\"2026-11-22T10:00:00Z\",\"payload\":{\"sequence\":1,\"workerStatus\":\"ACTIVE\"}}";
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
