package org.districompute.distributed.scheduler;

import java.util.List;

/** The built-in scheduling policies. */
public final class Policies {

    private Policies() {
    }

    /**
     * New instances of every built-in policy (round robin keeps a rotation, so each scheduler needs its own).
     *
     * @return the policies
     */
    public static List<SchedulingPolicy> builtIn() {
        return List.of(new RoundRobinPolicy(), new LeastLoadedPolicy(), new CapabilityAwarePolicy(),
                new PerformanceAwarePolicy(), new DynamicPolicy());
    }
}
