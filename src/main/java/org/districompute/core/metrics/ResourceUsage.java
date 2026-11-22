package org.districompute.core.metrics;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;

/**
 * Point-in-time snapshot of process-level resource counters. Subtract two snapshots to measure an interval.
 * Counters the JVM cannot provide are {@code -1}.
 *
 * @param processCpuNanos CPU time of the whole process (all threads, including JIT and GC)
 * @param gcCount collections summed over all collectors
 * @param gcMillis collection time summed over all collectors
 * @param threadAllocatedBytes heap bytes allocated so far by the <em>calling</em> thread
 */
public record ResourceUsage(long processCpuNanos, long gcCount, long gcMillis, long threadAllocatedBytes) {

    /**
     * Captures the counters now.
     *
     * @return the snapshot
     */
    public static ResourceUsage capture() {
        long gcCount = 0;
        long gcMillis = 0;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = collector.getCollectionCount();
            long time = collector.getCollectionTime();
            if (count < 0 || time < 0) {
                gcCount = -1;
                gcMillis = -1;
                break;
            }
            gcCount += count;
            gcMillis += time;
        }
        return new ResourceUsage(readProcessCpuNanos(), gcCount, gcMillis, currentThreadAllocatedBytes());
    }

    /**
     * Heap bytes allocated so far by the calling thread.
     *
     * @return the byte count, or {@code -1} if unsupported
     */
    public static long currentThreadAllocatedBytes() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        if (threads instanceof com.sun.management.ThreadMXBean hotspot
                && hotspot.isThreadAllocatedMemorySupported() && hotspot.isThreadAllocatedMemoryEnabled()) {
            return hotspot.getCurrentThreadAllocatedBytes();
        }
        return -1;
    }

    private static long readProcessCpuNanos() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean hotspot) {
            long cpu = hotspot.getProcessCpuTime();
            return cpu >= 0 ? cpu : -1;
        }
        return -1;
    }

    /**
     * Counter differences from {@code start} to this snapshot, keeping {@code -1} where either side is unknown.
     *
     * @param start earlier snapshot
     * @return the differences
     */
    public ResourceUsage minus(ResourceUsage start) {
        return new ResourceUsage(delta(processCpuNanos, start.processCpuNanos), delta(gcCount, start.gcCount),
                delta(gcMillis, start.gcMillis), delta(threadAllocatedBytes, start.threadAllocatedBytes));
    }

    private static long delta(long end, long begin) {
        return end < 0 || begin < 0 ? -1 : Math.max(0, end - begin);
    }
}
