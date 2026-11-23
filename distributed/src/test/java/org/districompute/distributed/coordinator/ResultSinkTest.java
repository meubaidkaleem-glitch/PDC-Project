package org.districompute.distributed.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.districompute.core.algorithm.ComputeAlgorithm;
import org.districompute.core.algorithm.matrix.Matrix;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.result.TaskResult;
import org.districompute.core.task.TaskSpec;
import org.districompute.core.task.TaskStatus;
import org.districompute.distributed.security.SharedSecret;
import org.districompute.distributed.task.ResultSink;
import org.districompute.distributed.task.SubmitOptions;
import org.districompute.distributed.task.TaskOutcome;
import org.districompute.distributed.worker.WorkerConfig;
import org.districompute.distributed.worker.WorkerNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The full result value is delivered once, verified, before completion, for distributed and local tasks. */
@Tag("integration")
class ResultSinkTest {

    private static final SharedSecret SECRET = SharedSecret.of("result-sink-test-secret");
    private static final Duration WAIT = Duration.ofSeconds(60);

    private Coordinator coordinator;
    private final List<WorkerNode> workers = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        coordinator = Coordinator.start(CoordinatorConfig.builder(SECRET).bindAddress("127.0.0.1").port(0)
                .heartbeat(Duration.ofMillis(200), Duration.ofMillis(600), Duration.ofMillis(2000))
                .tickInterval(Duration.ofMillis(20)).build());
        for (int i = 1; i <= 2; i++) {
            workers.add(WorkerNode.start(WorkerConfig.builder("127.0.0.1", coordinator.port(), SECRET)
                    .workerId("w" + i).maxConcurrentTasks(2).build()));
        }
        for (WorkerNode worker : workers) {
            assertTrue(worker.awaitRegistered(Duration.ofSeconds(10)), worker.lastError());
        }
    }

    @AfterEach
    void stop() {
        workers.forEach(WorkerNode::abort);
        coordinator.close();
    }

    @Test
    void aDistributedTaskDeliversItsVerifiedValueBeforeCompleting() throws Exception {
        List<Object> values = new CopyOnWriteArrayList<>();
        AtomicBoolean futureDoneDuringSink = new AtomicBoolean();
        AtomicReference<TaskHandle> handleRef = new AtomicReference<>();
        TaskSpec spec = matrix("sink-matrix", ExecutionMode.DISTRIBUTED_CPU);
        TaskHandle handle = coordinator.submit(spec, SubmitOptions.DEFAULTS, (result, value) -> {
            TaskHandle h = handleRef.get();
            futureDoneDuringSink.set(h != null && h.completion().isDone());
            values.add(value);
        });
        handleRef.set(handle);
        TaskOutcome outcome = handle.await(WAIT);
        assertEquals(TaskStatus.COMPLETED, outcome.result().status(), String.valueOf(outcome.result().error()));
        assertEquals(1, values.size(), "delivered exactly once");
        Matrix product = assertInstanceOf(Matrix.class, values.get(0));
        assertEquals(48, product.rows());
        assertEquals(40, product.cols());
        assertEquals(outcome.result().result().digest(), digestOf(outcome.result(), product),
                "the delivered value is the one Core verified");
        assertTrue(!futureDoneDuringSink.get(), "the sink runs before the completion future completes");
    }

    @Test
    void localTasksDeliverTheirValueToo() throws Exception {
        List<Object> values = new CopyOnWriteArrayList<>();
        for (ExecutionMode mode : List.of(ExecutionMode.SEQUENTIAL_CPU, ExecutionMode.MULTITHREADED_CPU)) {
            TaskSpec.Builder builder = TaskSpec.builder("sort.merge").taskId("sink-sort-" + mode)
                    .parameter("size", 50_000).mode(mode).createdAt(Instant.now());
            TaskSpec spec = mode == ExecutionMode.MULTITHREADED_CPU ? builder.maxThreads(2).build() : builder.build();
            TaskOutcome outcome = coordinator.submit(spec, SubmitOptions.DEFAULTS, (r, v) -> values.add(v))
                    .await(WAIT);
            assertEquals(TaskStatus.COMPLETED, outcome.result().status());
            long[] sorted = assertInstanceOf(long[].class, values.get(values.size() - 1));
            assertEquals(50_000, sorted.length);
            assertEquals(outcome.result().result().digest(), digestOf(outcome.result(), sorted));
        }
        assertEquals(2, values.size());
    }

    @Test
    void failedTasksDeliverNothingAndAFailingSinkDoesNotFailTheTask() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TaskSpec invalid = TaskSpec.builder("matrix.multiply").taskId("sink-invalid").parameter("rowsA", 4)
                .parameter("colsA", -3).parameter("colsB", 4).mode(ExecutionMode.DISTRIBUTED_CPU)
                .createdAt(Instant.now()).build();
        TaskOutcome failed = coordinator.submit(invalid, SubmitOptions.DEFAULTS, (r, v) -> calls.incrementAndGet())
                .await(WAIT);
        assertEquals(TaskStatus.FAILED, failed.result().status());
        assertEquals(0, calls.get());

        ResultSink broken = (r, v) -> {
            throw new IllegalStateException("storage unavailable");
        };
        TaskOutcome outcome = coordinator.submit(matrix("sink-broken", ExecutionMode.DISTRIBUTED_CPU),
                SubmitOptions.DEFAULTS, broken).await(WAIT);
        assertEquals(TaskStatus.COMPLETED, outcome.result().status());
        assertTrue(coordinator.task("sink-broken").isPresent(), "the finished task is still queryable");
    }

    private static TaskSpec matrix(String taskId, ExecutionMode mode) {
        return TaskSpec.builder("matrix.multiply").taskId(taskId).parameter("rowsA", 48).parameter("colsA", 32)
                .parameter("colsB", 40).mode(mode).createdAt(Instant.now()).build();
    }

    @SuppressWarnings("unchecked")
    private String digestOf(TaskResult result, Object value) {
        ComputeAlgorithm<Object, Object> algorithm = (ComputeAlgorithm<Object, Object>) coordinator.config()
                .registry().resolve(result.algorithm());
        return algorithm.summarize(value).digest();
    }
}
