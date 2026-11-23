package org.districompute.app.system;

import org.districompute.core.CoreVersion;
import org.districompute.distributed.DistributedVersion;
import org.districompute.distributed.protocol.ProtocolVersion;

/**
 * Versions of the integrated components, read from the components themselves (never hard-coded here).
 *
 * @param application this application's version
 * @param core DistriCompute-Core version
 * @param distributed DistriCompute-Distributed version
 * @param protocol worker protocol version spoken by the embedded coordinator
 */
public record ComponentVersions(String application, String core, String distributed, String protocol) {

    /**
     * Reads the versions.
     *
     * @param applicationVersion this application's version
     * @return the versions
     */
    public static ComponentVersions current(String applicationVersion) {
        return new ComponentVersions(applicationVersion, CoreVersion.version(), DistributedVersion.version(),
                ProtocolVersion.CURRENT);
    }
}
