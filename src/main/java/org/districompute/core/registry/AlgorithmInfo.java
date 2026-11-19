package org.districompute.core.registry;

import java.util.Objects;
import org.districompute.core.algorithm.AlgorithmDescriptor;

/**
 * Catalog entry: an algorithm's static description plus its derived execution capabilities.
 *
 * @param descriptor static description, including the parameter schema
 * @param capabilities derived execution capabilities
 */
public record AlgorithmInfo(AlgorithmDescriptor descriptor, AlgorithmCapabilities capabilities) {

    /** Validates the components. */
    public AlgorithmInfo {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(capabilities, "capabilities");
    }
}
