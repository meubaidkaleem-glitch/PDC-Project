package org.districompute.core.registry;

import java.util.List;
import org.districompute.core.algorithm.ComputeAlgorithm;
import org.districompute.core.algorithm.matrix.MatrixAddAlgorithm;
import org.districompute.core.algorithm.matrix.MatrixMultiplyAlgorithm;
import org.districompute.core.algorithm.primes.PrimeCountingAlgorithm;
import org.districompute.core.algorithm.sort.MergeSortAlgorithm;

/**
 * Provider of the algorithms that ship with DistriCompute-Core. It is registered in
 * {@code META-INF/services/org.districompute.core.registry.AlgorithmProvider}.
 */
public final class BuiltInAlgorithms implements AlgorithmProvider {

    @Override
    public List<ComputeAlgorithm<?, ?>> algorithms() {
        return List.of(new PrimeCountingAlgorithm(), new MergeSortAlgorithm(), new MatrixAddAlgorithm(),
                new MatrixMultiplyAlgorithm());
    }
}
