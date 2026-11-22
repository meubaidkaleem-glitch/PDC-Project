package org.districompute.distributed.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.districompute.core.task.TaskStatus;
import org.districompute.distributed.coordinator.Coordinator;
import org.districompute.distributed.coordinator.CoordinatorConfig;
import org.districompute.distributed.coordinator.TaskHandle;
import org.districompute.distributed.metrics.CoordinatorMetrics;
import org.districompute.distributed.metrics.DistributedMetrics;
import org.districompute.distributed.protocol.Envelope;
import org.districompute.distributed.protocol.ErrorPayload;
import org.districompute.distributed.protocol.MessageType;
import org.districompute.distributed.protocol.ProtocolErrorCode;
import org.districompute.distributed.security.SharedSecret;
import org.districompute.distributed.testing.Await;
import org.districompute.distributed.testing.CoreFixtures;
import org.districompute.distributed.testing.ProtocolClient;
import org.districompute.distributed.worker.WorkerConfig;
import org.districompute.distributed.worker.WorkerNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The events and metrics a real run produces, as the Application would see them. */
@Tag("integration")
class RuntimeEventsTest {

    private static final SharedSecret SECRET = SharedSecret.of("runtime-events-secret!");

    @Test
    void aRunWithAFailureProducesOrderedEventsAndMetrics() throws IOException, Exception {
        try (Coordinator coordinator = Coordinator.start(CoordinatorConfig.builder(SECRET).bindAddress("127.0.0.1")
                .port(0).heartbeat(Duration.ofMillis(200), Duration.ofMillis(600), Duration.ofMillis(2000))
                .tickInterval(Duration.ofMillis(20)).build())) {
            List<RuntimeEvent> events = new CopyOnWriteArrayList<>();
            EventBus.Subscription subscription = coordinator.events().subscribe(events::add, 0);
            ProtocolClient flaky = ProtocolClient.connect(coordinator.port());
            flaky.register("flaky", SECRET, CoreFixtures.capabilities(1));
            TaskHandle handle = coordinator.submit(CoreFixtures.primesTask("observed", 2_000_000, 2));
            Envelope task = flaky.receive(MessageType.TASK, Duration.ofSeconds(10));
            flaky.send(Envelope.builder(MessageType.ERROR).workerId("flaky").attempt(task.attemptRef())
                    .payload(new ErrorPayload(ProtocolErrorCode.TASK_FAILED, "simulated kernel failure", true, false,
                            null)));
            flaky.close();
            WorkerNode worker = WorkerNode.start(WorkerConfig.builder("127.0.0.1", coordinator.port(), SECRET)
                    .workerId("steady").maxConcurrentTasks(2).build());
            try {
                assertEquals(TaskStatus.COMPLETED, handle.await(Duration.ofSeconds(30)).result().status());
                Await.until(Duration.ofSeconds(5), () -> events.stream()
                        .anyMatch(e -> e.type() == EventType.TASK_FINISHED), "the final event");
                List<Long> sequences = events.stream().map(RuntimeEvent::sequence).toList();
                List<Long> sorted = new ArrayList<>(sequences);
                sorted.sort(null);
                assertEquals(sorted, sequences, "delivered in sequence order");
                assertEquals(EventType.COORDINATOR_STARTED, events.get(0).type());
                List<String> statuses = events.stream().filter(e -> e.type() == EventType.TASK_STATUS_CHANGED)
                        .map(e -> (String) e.details().get("to")).toList();
                assertEquals(List.of("VALIDATING", "QUEUED", "RUNNING", "AGGREGATING", "COMPLETED"), statuses);
                List<EventType> expected = List.of(EventType.WORKER_REGISTERED, EventType.TASK_SUBMITTED,
                        EventType.ATTEMPT_ASSIGNED, EventType.ATTEMPT_FAILED, EventType.PARTITION_RETRIED,
                        EventType.ATTEMPT_SUCCEEDED, EventType.TASK_PROGRESS, EventType.WORKER_DISCONNECTED);
                assertTrue(types(events).containsAll(expected), "missing: " + expected.stream()
                        .filter(t -> !types(events).contains(t)).toList());
                RuntimeEvent failed = events.stream().filter(e -> e.type() == EventType.ATTEMPT_FAILED).findFirst()
                        .orElseThrow();
                assertEquals("flaky", failed.workerId());
                assertEquals("observed", failed.taskId());
                assertEquals("exp-test", failed.experimentId());
                assertEquals(task.attemptId(), failed.attemptId());
                RuntimeEvent finished = events.stream().filter(e -> e.type() == EventType.TASK_FINISHED).findFirst()
                        .orElseThrow();
                assertEquals("COMPLETED", finished.details().get("status"));
                DistributedMetrics metrics = (DistributedMetrics) finished.details().get("metrics");
                // The flaky worker may also receive the second partition before its disconnection is noticed, so
                // there are one or two failures; every one of them must have led to exactly one retry.
                assertTrue(metrics.retries() >= 1);
                assertEquals(metrics.retries(), metrics.failedAttempts());

                CoordinatorMetrics cluster = coordinator.metrics();
                assertEquals(1, cluster.tasksCompleted());
                assertEquals(2, cluster.attemptsSucceeded());
                assertEquals(metrics.failedAttempts(), cluster.attemptsFailed());
                assertEquals(cluster.attemptsSucceeded() + cluster.attemptsFailed(), cluster.attemptsDispatched());
                assertEquals(metrics.retries(), cluster.partitionsRetried());
                assertTrue(cluster.bytesSent() > 0 && cluster.bytesReceived() > 0);
                assertEquals(1, cluster.workersByStatus().get("ACTIVE"));
                assertEquals(1, cluster.workersByStatus().get("DISCONNECTED"));
                assertEquals(2, cluster.totalSlots());
            } finally {
                subscription.close();
                worker.abort();
            }
        }
    }

    private static List<EventType> types(List<RuntimeEvent> events) {
        return events.stream().map(RuntimeEvent::type).distinct().toList();
    }
}
