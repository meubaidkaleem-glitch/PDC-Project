package org.districompute.distributed.communication;

import java.io.IOException;

/** A frame exceeded the configured size limit before its line feed arrived. The connection must be closed. */
public final class FrameTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param limit the limit in bytes
     */
    public FrameTooLargeException(int limit) {
        super("Frame exceeds the limit of " + limit + " bytes");
    }
}
