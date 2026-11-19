package org.districompute.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;
import org.districompute.core.algorithm.AlgorithmRef;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.partition.PartitioningOptions;
import org.junit.jupiter.api.Test;

class TaskSpecTest {

    @Test
    void builderAppliesDefaults() {
        TaskSpec spec = TaskSpec.builder("matrix.multiply").parameter("rowsA", 4).build();
        assertEquals("0.1", spec.schemaVersion());
        assertEquals(ExecutionMode.SEQUENTIAL_CPU, spec.constraints().mode());
        assertEquals(TaskPriority.NORMAL, spec.priority());
        assertEquals(PartitioningOptions.defaults(), spec.constraints().partitioning());
        assertNull(spec.algorithm().version());
        assertEquals(36, spec.taskId().length());
    }

    @Test
    void parametersAreNormalizedAndImmutable() {
        TaskSpec spec = TaskSpec.builder("sort.merge")
                .parameter("size", 10)
                .parameter("ratio", 0.5f)
                .parameter("values", new int[] {3, 1, 2})
                .build();
        assertEquals(10L, spec.parameters().get("size"));
        assertEquals(0.5d, spec.parameters().get("ratio"));
        assertEquals(List.of(3L, 1L, 2L), spec.parameters().get("values"));
        assertThrows(UnsupportedOperationException.class, () -> spec.parameters().put("x", 1L));
    }

    @Test
    void rejectsInvalidIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> TaskSpec.builder("matrix.multiply").taskId("").build());
        assertThrows(IllegalArgumentException.class,
                () -> TaskSpec.builder("matrix.multiply").taskId("has space").build());
        assertThrows(IllegalArgumentException.class,
                () -> TaskSpec.builder("matrix.multiply").experimentId("x".repeat(129)).build());
        assertThrows(IllegalArgumentException.class, () -> TaskSpec.builder("Matrix.Multiply"));
        assertThrows(IllegalArgumentException.class, () -> TaskSpec.builder("matrix"));
    }

    @Test
    void rejectsInvalidConstraintsAndValues() {
        assertThrows(IllegalArgumentException.class, () -> TaskSpec.builder("sort.merge").maxThreads(0).build());
        assertThrows(IllegalArgumentException.class, () -> TaskSpec.builder("sort.merge").timeoutMillis(0).build());
        assertThrows(IllegalArgumentException.class, () -> PartitioningOptions.ofPartitions(0));
        assertThrows(IllegalArgumentException.class,
                () -> TaskSpec.builder("sort.merge").parameter("size", new Object()).build());
    }

    @Test
    void rejectsIncompatibleSchemaVersions() {
        ExecutionConstraints constraints = ExecutionConstraints.of(ExecutionMode.SEQUENTIAL_CPU);
        for (String version : new String[] {"1.0", "0.2", "x", null}) {
            DistriComputeException e = assertThrows(DistriComputeException.class,
                    () -> new TaskSpec(version, "t1", null, AlgorithmRef.of("sort.merge"), Map.of(), constraints,
                            null, Instant.now()));
            assertEquals(ErrorCode.UNSUPPORTED_SCHEMA_VERSION, e.code(), String.valueOf(version));
        }
    }
}
