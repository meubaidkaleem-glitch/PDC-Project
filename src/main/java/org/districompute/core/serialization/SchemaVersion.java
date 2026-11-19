package org.districompute.core.serialization;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;

/**
 * Schema version of the shared contract objects (integration contract, section 13).
 *
 * <p>Rules: readers reject a different MAJOR version. While MAJOR is {@code 0} (draft), the MINOR version must match
 * as well. Within a compatible version, unknown JSON fields are ignored.
 */
public final class SchemaVersion {

    /** Contract schema version implemented by this library. */
    public static final String CURRENT = "0.1";

    private static final Pattern FORMAT = Pattern.compile("^(0|[1-9]\\d{0,3})\\.(0|[1-9]\\d{0,3})$");
    private static final int CURRENT_MAJOR = 0;
    private static final int CURRENT_MINOR = 1;

    private SchemaVersion() {
    }

    /**
     * Checks that a document's schema version can be read by this library.
     *
     * @param version the {@code schemaVersion} value of a document
     * @return the version, unchanged
     * @throws DistriComputeException with {@link ErrorCode#UNSUPPORTED_SCHEMA_VERSION} if it is missing, malformed or
     *     incompatible
     */
    public static String requireCompatible(String version) {
        if (version == null) {
            throw new DistriComputeException(ErrorCode.UNSUPPORTED_SCHEMA_VERSION,
                    "Missing schemaVersion (this library reads " + CURRENT + ")");
        }
        Matcher matcher = FORMAT.matcher(version);
        if (!matcher.matches()) {
            throw new DistriComputeException(ErrorCode.UNSUPPORTED_SCHEMA_VERSION,
                    "Malformed schemaVersion '" + version + "' (expected MAJOR.MINOR)");
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        boolean compatible = major == CURRENT_MAJOR && (major != 0 || minor == CURRENT_MINOR);
        if (!compatible) {
            throw new DistriComputeException(ErrorCode.UNSUPPORTED_SCHEMA_VERSION,
                    "Unsupported schemaVersion " + version + " (this library reads " + CURRENT + ")");
        }
        return version;
    }
}
