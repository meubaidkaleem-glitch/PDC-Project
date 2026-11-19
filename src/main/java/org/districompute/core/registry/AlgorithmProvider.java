package org.districompute.core.registry;

import java.util.List;
import org.districompute.core.algorithm.ComputeAlgorithm;

/**
 * Discovery hook for algorithms, loaded with {@link java.util.ServiceLoader}. A library that adds algorithms
 * lists its provider class in {@code META-INF/services/org.districompute.core.registry.AlgorithmProvider}. The
 * engine needs no other change to pick them up.
 */
public interface AlgorithmProvider {

    /**
     * Returns the algorithms contributed by this provider.
     *
     * @return algorithm instances (stateless, thread-safe)
     */
    List<ComputeAlgorithm<?, ?>> algorithms();
}
