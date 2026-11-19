package org.districompute.core.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;
import org.districompute.core.ErrorInfo;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.fixtures.SumOfSquaresAlgorithm;
import org.districompute.core.partition.PartitioningOptions;
import org.districompute.core.registry.AlgorithmInfo;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.core.task.TaskPriority;
import org.districompute.core.task.TaskSpec;
import org.districompute.core.task.TaskStatus;
import org.districompute.core.validation.ValidationException;
import org.districompute.core.validation.Violation;
import org.junit.jupiter.api.Test;

class ContractJsonTest {

    private static TaskSpec sampleTask() {
        return TaskSpec.builder("matrix.multiply")
                .taskId("8b0e3d1c-5a52-4f87-9b8e-2b1f0c7e6a10")
                .experimentId("exp-matmul-scaling")
                .version("1.0.0")
                .parameter("rowsA", 1024)
                .parameter("tolerance", 1e-9)
                .parameter("distribution", "UNIFORM")
                .parameter("values", List.of(1, 2, 3))
                .mode(ExecutionMode.MULTITHREADED_CPU)
                .maxThreads(4)
                .partitioning(PartitioningOptions.ofPartitions(16))
                .timeoutMillis(600_000)
                .priority(TaskPriority.HIGH)
                .createdAt(Instant.parse("2026-11-19T10:15:30.123Z"))
                .build();
    }

    @Test
    void taskSpecRoundTripsLosslessly() {
        TaskSpec original = sampleTask();
        String json = ContractJson.toJson(original);
        assertEquals(original, ContractJson.fromJson(json, TaskSpec.class));
    }

    @Test
    void taskSpecUsesTheDocumentedShape() {
        String json = ContractJson.toJson(sampleTask());
        assertTrue(json.startsWith("{\"schemaVersion\":\"0.1\",\"taskId\":\"8b0e3d1c"), json);
        assertTrue(json.contains("\"algorithm\":{\"id\":\"matrix.multiply\",\"version\":\"1.0.0\"}"), json);
        assertTrue(json.contains("\"createdAt\":\"2026-11-19T10:15:30.123Z\""), json);
        assertTrue(json.contains("\"partitioning\":{\"targetPartitions\":16}"), json);
        assertFalse(json.contains("null"), "null fields must be omitted: " + json);
    }

    @Test
    void ignoresUnknownFieldsWithinTheSameSchemaVersion() {
        String json = ContractJson.toJson(sampleTask()).replaceFirst("\\{", "{\"futureField\":{\"a\":1},");
        assertEquals(sampleTask(), ContractJson.fromJson(json, TaskSpec.class));
    }

    @Test
    void rejectsIncompatibleSchemaVersion() {
        String json = ContractJson.toJson(sampleTask())
                .replace("\"schemaVersion\":\"0.1\"", "\"schemaVersion\":\"1.0\"");
        DistriComputeException e = assertThrows(DistriComputeException.class,
                () -> ContractJson.fromJson(json, TaskSpec.class));
        assertEquals(ErrorCode.UNSUPPORTED_SCHEMA_VERSION, e.code());
    }

    @Test
    void reportsMalformedDocumentsAsValidationErrors() {
        assertMalformed("{\"schemaVersion\":\"0.1\"");
        assertMalformed("[]");
        assertMalformed(ContractJson.toJson(sampleTask()).replace("\"maxThreads\":4", "\"maxThreads\":0"));
        assertMalformed(ContractJson.toJson(sampleTask()).replace("matrix.multiply", "Matrix Multiply"));
        assertMalformed(ContractJson.toJson(sampleTask()) + "{}");
    }

    @Test
    void serializesCatalogEntriesAndErrors() {
        AlgorithmInfo info = AlgorithmRegistry.of(new SumOfSquaresAlgorithm()).catalog().get(0);
        String json = ContractJson.toJson(info);
        assertTrue(json.contains("\"id\":\"test.sum-of-squares\""), json);
        assertTrue(json.contains("\"supportedModes\":[\"SEQUENTIAL_CPU\"]"), json);
        assertTrue(json.contains("\"name\":\"n\",\"type\":\"INTEGER\""), json);
        assertEquals(info, ContractJson.fromJson(json, AlgorithmInfo.class));

        ErrorInfo error = new ErrorInfo(ErrorCode.VALIDATION_FAILED, "bad", false, TaskStatus.VALIDATING, null,
                List.of(Violation.ofParameter("rowsA", Violation.Codes.ABOVE_MAXIMUM, "too big")));
        assertEquals(error, ContractJson.fromJson(ContractJson.toJson(error), ErrorInfo.class));
    }

    private static void assertMalformed(String json) {
        ValidationException e = assertThrows(ValidationException.class,
                () -> ContractJson.fromJson(json, TaskSpec.class));
        assertEquals(Violation.Codes.MALFORMED_DOCUMENT, e.violations().get(0).code());
    }
}
