package org.districompute.core.algorithm;

import java.util.Objects;

/**
 * Reference to an algorithm in a task or result.
 *
 * @param id algorithm identifier
 * @param version requested version (optional in requests) or resolved version (always present in results)
 */
public record AlgorithmRef(AlgorithmId id, AlgorithmVersion version) {

    /** Validates the components. */
    public AlgorithmRef {
        Objects.requireNonNull(id, "id");
    }

    /**
     * References the registered version of an algorithm.
     *
     * @param id algorithm identifier
     * @return the reference without a version requirement
     */
    public static AlgorithmRef of(String id) {
        return new AlgorithmRef(AlgorithmId.of(id), null);
    }

    /**
     * References a specific version of an algorithm.
     *
     * @param id algorithm identifier
     * @param version semantic version
     * @return the reference
     */
    public static AlgorithmRef of(String id, String version) {
        return new AlgorithmRef(AlgorithmId.of(id), AlgorithmVersion.parse(version));
    }

    @Override
    public String toString() {
        return version == null ? id.value() : id.value() + "@" + version;
    }
}
