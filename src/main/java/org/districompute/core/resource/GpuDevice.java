package org.districompute.core.resource;

import java.util.Objects;

/**
 * A GPU device reported by a {@link GpuBackend}.
 *
 * @param name device name
 * @param vendor vendor name
 * @param api API and version used to reach it, for example {@code "OpenCL 3.0"}
 * @param globalMemoryBytes device memory
 * @param computeUnits compute units or streaming multiprocessors
 * @param supportsDoublePrecision whether 64-bit floating point is available (required for exact parity with CPU
 *     results' precision)
 */
public record GpuDevice(String name, String vendor, String api, long globalMemoryBytes, int computeUnits,
        boolean supportsDoublePrecision) {

    /** Validates the components. */
    public GpuDevice {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(vendor, "vendor");
        Objects.requireNonNull(api, "api");
    }
}
