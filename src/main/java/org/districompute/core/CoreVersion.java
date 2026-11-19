package org.districompute.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Version of the DistriCompute-Core library, recorded in every result and benchmark report so that a
 * measurement can always be traced back to the code that produced it.
 */
public final class CoreVersion {

    private static final String RESOURCE = "core-version.properties";
    private static final String VERSION = loadVersion();

    private CoreVersion() {
    }

    /**
     * Returns the library version, for example {@code 0.1.0-SNAPSHOT}.
     *
     * @return the Maven project version this library was built from
     */
    public static String version() {
        return VERSION;
    }

    private static String loadVersion() {
        try (InputStream in = CoreVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + RESOURCE + " next to " + CoreVersion.class);
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
