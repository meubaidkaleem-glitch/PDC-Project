package org.districompute.core.algorithm;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Static description of an algorithm, published in the catalog for discovery.
 *
 * @param id permanent identifier
 * @param version contract version
 * @param displayName human-readable name
 * @param description what the algorithm computes
 * @param category workload family
 * @param parameters parameter schema, in display order
 * @param resultType short name of the result type, for example {@code "matrix"} or {@code "count"}
 * @param verificationMethod how results are verified, in plain words
 * @param limitations known limitations and constraints, in plain words
 */
public record AlgorithmDescriptor(
        AlgorithmId id,
        AlgorithmVersion version,
        String displayName,
        String description,
        AlgorithmCategory category,
        List<ParameterSpec> parameters,
        String resultType,
        String verificationMethod,
        List<String> limitations) {

    /** Validates the components. */
    public AlgorithmDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        requireText("displayName", displayName);
        requireText("description", description);
        Objects.requireNonNull(category, "category");
        parameters = List.copyOf(parameters);
        Set<String> names = new HashSet<>();
        for (ParameterSpec parameter : parameters) {
            if (!names.add(parameter.name())) {
                throw new IllegalArgumentException("Duplicate parameter '" + parameter.name() + "' in " + id);
            }
        }
        requireText("resultType", resultType);
        requireText("verificationMethod", verificationMethod);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    /**
     * Returns a reference to exactly this algorithm version.
     *
     * @return the reference
     */
    public AlgorithmRef ref() {
        return new AlgorithmRef(id, version);
    }

    private static void requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
