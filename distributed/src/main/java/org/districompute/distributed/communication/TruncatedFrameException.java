package org.districompute.distributed.communication;

import java.io.IOException;

/** The stream ended in the middle of a frame (bytes without a final line feed). */
public final class TruncatedFrameException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param bytes number of bytes received without a line feed
     */
    public TruncatedFrameException(int bytes) {
        super("Connection closed in the middle of a frame (" + bytes + " bytes without a line feed)");
    }
}
