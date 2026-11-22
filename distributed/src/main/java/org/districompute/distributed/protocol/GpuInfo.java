package org.districompute.distributed.protocol;

import java.util.List;

/**
 * GPU availability as detected by Core on the worker. {@code available} is only {@code true} when Core found a
 * working backend; a worker never claims a GPU on configuration alone.
 *
 * @param available whether a usable backend was detected
 * @param backends names of the detected backends
 * @param unavailableReasons why no backend is usable (empty if one is)
 */
public record GpuInfo(boolean available, List<String> backends, List<String> unavailableReasons) {

    /** Copies the lists. */
    public GpuInfo {
        backends = Checks.boundedCopy(backends, "backends", 16);
        unavailableReasons = Checks.boundedCopy(unavailableReasons, "unavailableReasons", 16);
    }
}
