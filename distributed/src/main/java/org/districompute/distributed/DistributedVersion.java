package org.districompute.distributed;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Version of the DistriCompute-Distributed runtime. It is reported by workers at registration and by the
 * coordinator in its health information, so a measurement or failure can be traced to the code that produced it.
 */
public final class DistributedVersion {

    private static final String RESOURCE = "distributed-version.properties";
    private static final String VERSION = loadVersion();

    private DistributedVersion() {
    }

    /**
     * Returns the runtime version, for example {@code 0.1.0-SNAPSHOT}.
     *
     * @return the Maven project version this runtime was built from
     */
    public static String version() {
        return VERSION;
    }

    private static String loadVersion() {
        try (InputStream in = DistributedVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + RESOURCE + " next to "
                        + DistributedVersion.class);
            }
            Properties properties = new Properties();
            properties.load(in);
            String value = properties.getProperty("version");
            if (value == null || value.isBlank() || value.startsWith("${")) {
                throw new IllegalStateException("Unfiltered or empty version in " + RESOURCE);
            }
            return value.trim();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + RESOURCE, e);
        }
    }
}
