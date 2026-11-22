package org.districompute.distributed.security;

/** A registration proof is invalid, stale or replayed. The message is safe to send to the peer. */
public final class AuthenticationException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message why the proof was rejected
     */
    public AuthenticationException(String message) {
        super(message);
    }
}
