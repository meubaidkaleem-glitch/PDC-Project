package org.districompute.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.districompute.core.execution.CancellationToken;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.execution.LocalExecutionEngine;
import org.districompute.core.execution.ProgressReporter;
import org.districompute.core.partition.PartialResultMessage;
import org.districompute.core.partition.PartitionDescriptor;
import org.districompute.core.partition.PartitionWorker;
import org.districompute.core.partition.PartitionedTaskSession;
import org.districompute.core.partition.WorkUnitMessage;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.core.result.TaskResult;
import org.districompute.core.serialization.ContractJson;
import org.districompute.core.task.TaskSpec;
import org.districompute.core.task.TaskStatus;
import org.districompute.core.validation.Violation;
import org.junit.jupiter.api.Test;

/**
 * End-to-end check of the published examples: each task file in {@code examples/tasks} is parsed from JSON and
 * executed through the real path (local engine, or coordinator session plus workers exchanging JSON). Result files
 * in {@code examples/results} must be valid contract documents.
 */
class ExamplesTest {

    private static List<Path> files(String directory, String suffix) throws IOException {
        try (Stream<Path> stream = Files.list(Path.of(directory))) {
            return stream.filter(p -> p.toString().endsWith(suffix)).sorted().toList();
        }
    }

    @Test
    void everyExampleTaskRunsToItsExpectedOutcome() throws IOException {
        List<Path> tasks = files("examples/tasks", ".json");
        assertTrue(tasks.size() >= 8);
        AlgorithmRegistry registry = AlgorithmRegistry.loadDefault();
        try (LocalExecutionEngine engine = LocalExecutionEngine.withDefaults()) {
            for (Path file : tasks) {
                TaskSpec task = ContractJson.fromJson(Files.readString(file, StandardCharsets.UTF_8), TaskSpec.class);
                TaskResult result = task.constraints().mode() == ExecutionMode.DISTRIBUTED_CPU
                        ? runDistributed(registry, task) : engine.execute(task).taskResult();
                if (file.getFileName().toString().startsWith("invalid-")) {
                    assertEquals(TaskStatus.FAILED, result.status(), file.toString());
                    assertTrue(result.error().violations().stream()
                            .anyMatch(v -> v.code().equals(Violation.Codes.DIMENSION_MISMATCH)), file.toString());
                } else {
                    assertEquals(TaskStatus.COMPLETED, result.status(), () -> file + ": " + result.error());
                    assertTrue(result.verification().passed());
                }
                assertEquals(result, ContractJson.fromJson(ContractJson.toJson(result), TaskResult.class));
            }
        }
    }

    private static TaskResult runDistributed(AlgorithmRegistry registry, TaskSpec task) {
        PartitionedTaskSession session = PartitionedTaskSession.open(registry, task, 4);
        PartitionWorker worker = new PartitionWorker(registry, "example-worker");
        for (PartitionDescriptor descriptor : session.partitions()) {
            WorkUnitMessage message = ContractJson.fromJson(ContractJson.toJson(session.workUnit(descriptor.index(),
                    1)), WorkUnitMessage.class);
            PartialResultMessage reply = worker.execute(message, CancellationToken.NONE, ProgressReporter.NONE);
            assertTrue(session.accept(ContractJson.fromJson(ContractJson.toJson(reply), PartialResultMessage.class))
                    .accepted());
        }
        return session.complete();
    }

    @Test
    void publishedResultExamplesAreValidContractDocuments() throws IOException {
        List<Path> results = files("examples/results", ".result.json");
        assertFalse(results.isEmpty());
        for (Path file : results) {
            TaskResult result = ContractJson.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    TaskResult.class);
            assertTrue(result.status().isTerminal(), file.toString());
        }
    }
}
