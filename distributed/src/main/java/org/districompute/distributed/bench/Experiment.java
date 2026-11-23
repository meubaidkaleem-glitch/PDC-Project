package org.districompute.distributed.bench;

import java.util.List;
import java.util.Objects;

/**
 * A group of configurations that answers one question.
 *
 * @param id short id (also accepted by {@code bench --only})
 * @param title heading in the report
 * @param question what the experiment measures
 * @param configurations the settings, run in this order
 */
public record Experiment(String id, String title, String question, List<Configuration> configurations) {

    /** Validates and copies the components. */
    public Experiment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(question, "question");
        configurations = List.copyOf(configurations);
        if (configurations.stream().map(Configuration::label).distinct().count() != configurations.size()) {
            throw new IllegalArgumentException("Configuration labels must be unique within experiment " + id);
        }
    }
}
