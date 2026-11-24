package org.districompute.app.monitoring;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.districompute.app.persistence.ExperimentRepository;
import org.districompute.app.persistence.ExperimentRun;
import org.districompute.app.persistence.ExperimentRunRepository;
import org.springframework.stereotype.Component;

/** Caches which experiment, owner and run a runtime identifier belongs to. */
@Component
public class ExperimentDirectory {

    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final int MAX_ENTRIES = 20_000;

    private final ExperimentRepository experiments;
    private final ExperimentRunRepository runs;
    private final Map<String, Long> owners = new ConcurrentHashMap<>();
    private final Map<String, Long> runIds = new ConcurrentHashMap<>();

    ExperimentDirectory(ExperimentRepository experiments, ExperimentRunRepository runs) {
        this.experiments = experiments;
        this.runs = runs;
    }

    /**
     * The owner of an experiment.
     *
     * @param experimentId experiment id (any string)
     * @return the owner's id, if the experiment exists
     */
    public Optional<Long> owner(String experimentId) {
        if (experimentId == null || !UUID.matcher(experimentId).matches()) {
            return Optional.empty();
        }
        Long cached = owners.get(experimentId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<Long> owner = experiments.findById(experimentId).map(e -> e.getOwner().getId());
        owner.ifPresent(id -> remember(owners, experimentId, id));
        return owner;
    }

    /**
     * The run of a Core task id.
     *
     * @param coreTaskId Core task id
     * @return the run id, if known
     */
    public Optional<Long> run(String coreTaskId) {
        if (coreTaskId == null) {
            return Optional.empty();
        }
        Long cached = runIds.get(coreTaskId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<Long> id = runs.findByCoreTaskId(coreTaskId).map(ExperimentRun::getId);
        id.ifPresent(value -> remember(runIds, coreTaskId, value));
        return id;
    }

    /**
     * Forgets a deleted experiment.
     *
     * @param experimentId experiment
     */
    public void forget(String experimentId) {
        owners.remove(experimentId);
        runIds.keySet().removeIf(k -> k.startsWith(experimentId));
    }

    private static void remember(Map<String, Long> map, String key, Long value) {
        if (map.size() > MAX_ENTRIES) {
            map.clear();
        }
        map.put(key, value);
    }
}
