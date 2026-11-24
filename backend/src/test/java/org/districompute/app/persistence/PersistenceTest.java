package org.districompute.app.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.districompute.app.persistence.Enums.ExperimentStatus;
import org.districompute.app.persistence.Enums.InputSource;
import org.districompute.app.persistence.Enums.MetricSource;
import org.districompute.app.persistence.Enums.Role;
import org.districompute.app.persistence.Enums.RunStatus;
import org.districompute.app.persistence.Enums.Severity;
import org.districompute.app.persistence.Enums.ValidationStatus;
import org.districompute.app.testing.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Flyway migrations on a real MySQL, Hibernate's schema validation, and the constraints of the schema. */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestDatabase.class)
class PersistenceTest {

    @Autowired
    private AppUserRepository users;
    @Autowired
    private ExperimentRepository experiments;
    @Autowired
    private ExperimentRunRepository runs;
    @Autowired
    private PartitionTaskRepository partitions;
    @Autowired
    private RunMetricRepository metrics;
    @Autowired
    private ExperimentResultRepository results;
    @Autowired
    private ExecutionEventRepository events;
    @Autowired
    private WorkerRepository workers;
    @Autowired
    private JdbcTemplate jdbc;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Test
    void anExperimentWithItsRunPartitionsMetricsResultAndEventsRoundTrips() {
        AppUser owner = users.save(new AppUser("alice", "$2a$10$hash", Role.USER, now));
        Experiment experiment = experiment(owner);
        experiments.saveAndFlush(experiment);

        ExperimentRun run = new ExperimentRun(experiment.getId(), 1, "DISTRIBUTED_CPU");
        run.setStatus(RunStatus.COMPLETED);
        run.setWallTimeMs(123.5);
        run.setVerificationPassed(true);
        runs.saveAndFlush(run);
        assertEquals(experiment.getId() + "-r1", run.getCoreTaskId());

        PartitionTask p0 = new PartitionTask(experiment.getId(), run.getId(), 0, run.getCoreTaskId() + "/p0");
        p0.setStatus("COMPLETED");
        p0.setWorkerId("w1");
        p0.setAttemptCount(1);
        partitions.saveAndFlush(p0);
        metrics.saveAndFlush(new RunMetric(experiment.getId(), run.getId(), "wall_time", 123.5, "ms",
                MetricSource.APPLICATION, now));

        ExperimentResult result = new ExperimentResult(experiment.getId(), run.getId());
        result.setResultType("matrix");
        result.setDigest("sha256:" + "a".repeat(64));
        result.setValidationStatus(ValidationStatus.VERIFIED);
        result.setSummary("{\"rows\":4,\"cols\":4}");
        result.setCreatedAt(now);
        results.saveAndFlush(result);

        events.saveAndFlush(new ExecutionEvent(experiment.getId(), run.getId(), null, "w1", 42L, "TASK_FINISHED",
                Severity.INFO, "done", "{\"status\":\"COMPLETED\"}", false, now));

        Experiment loaded = experiments.findById(experiment.getId()).orElseThrow();
        assertEquals(ExperimentStatus.QUEUED, loaded.getStatus());
        assertTrue(loaded.getParameters().contains("\"rowsA\""), loaded.getParameters());
        assertEquals(1, runs.findByExperimentIdOrderByRunIndexAsc(experiment.getId()).size());
        assertEquals(1, partitions.countByWorkerId("w1"));
        assertEquals("ms", metrics.findByExperimentIdOrderByRunIdAscMetricNameAsc(experiment.getId()).get(0).getUnit());
        assertTrue(results.findByRunId(run.getId()).orElseThrow().getSummary().contains("rows"));
        assertTrue(events.existsByRuntimeSequenceAndSimulated(42L, false));
        assertEquals(1, events.findByExperimentIdAndIdGreaterThanOrderByIdAsc(experiment.getId(), 0,
                PageRequest.of(0, 10)).size());
        List<Object[]> counts = experiments.countByStatus(owner.getId());
        assertEquals(ExperimentStatus.QUEUED, counts.get(0)[0]);

        // Deleting an experiment removes its runs, partitions, metrics, results and events.
        experiments.delete(loaded);
        experiments.flush();
        assertEquals(0, jdbc.queryForObject("select count(*) from experiment_runs", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from tasks", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from execution_events", Integer.class));
    }

    @Test
    void theSchemaEnforcesItsConstraints() {
        AppUser owner = users.save(new AppUser("bob", "$2a$10$hash", Role.ADMIN, now));
        Experiment experiment = experiments.saveAndFlush(experiment(owner));
        runs.saveAndFlush(new ExperimentRun(experiment.getId(), 1, "SEQUENTIAL_CPU"));
        assertThrows(DataIntegrityViolationException.class,
                () -> runs.saveAndFlush(new ExperimentRun(experiment.getId(), 1, "SEQUENTIAL_CPU")),
                "run indexes and Core task ids are unique");
        assertThrows(DataIntegrityViolationException.class, () -> users.saveAndFlush(
                new AppUser("bob", "x", Role.USER, now)), "user names are unique");
        // MySQL reports check constraints (3819) and invalid JSON (3140) with codes Spring does not classify.
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "update experiments set status = 'EXPLODED' where id = ?", experiment.getId()), "status check");
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "update experiments set parameters = 'not json' where id = ?", experiment.getId()), "JSON column");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into run_metrics (experiment_id, run_id, metric_name, metric_value, unit, source, recorded_at)"
                        + " values (?, 999999, 'x', 1, 'ms', 'APPLICATION', now())", experiment.getId()),
                "metrics need an existing run");
    }

    @Test
    void workersKeepTheirCapabilitiesAndApproval() {
        WorkerEntity worker = new WorkerEntity("lab-pc-07", "ACTIVE", now);
        worker.setCapabilities("{\"cpuCores\":8}");
        worker.setApproved(true);
        worker.setApprovedBy("admin");
        workers.saveAndFlush(worker);
        WorkerEntity loaded = workers.findById("lab-pc-07").orElseThrow();
        assertTrue(loaded.isApproved());
        assertTrue(loaded.getCapabilities().contains("cpuCores"));
    }

    private Experiment experiment(AppUser owner) {
        Experiment experiment = new Experiment(UUID.randomUUID().toString(), owner, now);
        experiment.setName("Matrix 4x4");
        experiment.setCategory("LINEAR_ALGEBRA");
        experiment.setAlgorithmId("matrix.multiply");
        experiment.setAlgorithmVersion("1.0.0");
        experiment.setParameters("{\"rowsA\":4,\"colsA\":4,\"colsB\":4}");
        experiment.setInputSource(InputSource.GENERATED);
        experiment.setExecutionMode("DISTRIBUTED_CPU");
        experiment.setConfiguration("{\"repetitions\":1}");
        return experiment;
    }
}
