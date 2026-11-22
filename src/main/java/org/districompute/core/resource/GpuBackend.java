package org.districompute.core.resource;

import java.util.List;
import org.districompute.core.algorithm.AlgorithmId;
import org.districompute.core.algorithm.ComputeAlgorithm;
import org.districompute.core.execution.ExecutionContext;

/**
 * Optional GPU backend, discovered with {@link java.util.ServiceLoader}. DistriCompute-Core ships <b>no</b>
 * implementation: no GPU was available to validate one, and an operation must never be labelled GPU-accelerated
 * unless it really runs on a GPU. {@code docs/gpu-backend.md} describes the integration path (JOCL/OpenCL first,
 * matrix multiplication first).
 *
 * <p>Contract for implementations:
 * <ul>
 *   <li>{@link #probe()} never throws. Missing drivers or native libraries are reported as unavailability with a
 *       reason.</li>
 *   <li>{@link #execute} runs the algorithm on a GPU. The engine verifies the result with the algorithm's CPU
 *       verifier, which is a trusted CPU reference.</li>
 *   <li>Host-to-device transfer, kernel time and device-to-host transfer are reported separately.</li>
 * </ul>
 */
public interface GpuBackend {

    /**
     * Backend name, for example {@code "jocl-opencl"}.
     *
     * @return the name
     */
    String name();

    /**
     * Detects usable devices. Must not throw.
     *
     * @return the probe result
     */
    Probe probe();

    /**
     * Whether this backend implements an algorithm.
     *
     * @param algorithm algorithm identifier
     * @return {@code true} if supported
     */
    boolean supports(AlgorithmId algorithm);

    /**
     * Executes an algorithm on the GPU.
     *
     * @param algorithm the algorithm
     * @param input materialized input
     * @param context execution context
     * @param <I> input type
     * @param <R> result type
     * @return the result, to be verified by the caller on the CPU
     */
    <I, R> R execute(ComputeAlgorithm<I, R> algorithm, I input, ExecutionContext context);

    /**
     * Outcome of probing a backend.
     *
     * @param available whether at least one usable device exists
     * @param devices usable devices
     * @param reason why the backend is unavailable, or an empty string
     */
    record Probe(boolean available, List<GpuDevice> devices, String reason) {

        /** Copies the device list. */
        public Probe {
            devices = List.copyOf(devices);
            reason = reason == null ? "" : reason;
        }

        /**
         * An unavailable result.
         *
         * @param reason why
         * @return the probe result
         */
        public static Probe unavailable(String reason) {
            return new Probe(false, List.of(), reason);
        }
    }
}
