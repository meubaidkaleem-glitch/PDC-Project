package org.districompute.distributed.protocol;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Version of the coordinator–worker wire protocol ({@code protocolVersion} in every message).
 *
 * <p>Rules, the same as Core's contract schema versions: a different MAJOR version is rejected. While MAJOR is
 * {@code 0} (draft), the MINOR version must match as well. Within a compatible version, unknown JSON fields are
 * ignored, so a sender may add optional fields without breaking older receivers.
 */
public final class ProtocolVersion {

    /** Protocol version implemented by this runtime. */
    public static final String CURRENT = "0.1";

    private static final Pattern FORMAT = Pattern.compile("^(0|[1-9]\\d{0,3})\\.(0|[1-9]\\d{0,3})$");
    private static final int CURRENT_MAJOR = 0;
    private static final int CURRENT_MINOR = 1;

    private ProtocolVersion() {
    }

    /**
     * Whether a peer's protocol version can be understood by this runtime.
     *
     * @param version the {@code protocolVersion} of a message, possibly {@code null}
     * @return {@code true} if compatible
     */
    public static boolean isSupported(String version) {
        if (version == null) {
            return false;
        }
        Matcher matcher = FORMAT.matcher(version);
        if (!matcher.matches()) {
            return false;
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        return major == CURRENT_MAJOR && (major != 0 || minor == CURRENT_MINOR);
    }
}
