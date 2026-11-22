package org.districompute.distributed.communication;

/** Why a connection was closed. */
public enum CloseReason {
    /** This side closed it (shutdown, policy, protocol violation). */
    LOCAL,
    /** The peer closed the stream cleanly. */
    PEER_CLOSED,
    /** Reading or writing failed (reset, broken pipe, network failure). */
    IO_ERROR,
    /** The stream ended in the middle of a frame. */
    TRUNCATED_FRAME,
    /** A frame exceeded the size limit. */
    FRAME_TOO_LARGE,
    /** The peer did not read fast enough and the bounded outbound queue filled up. */
    OUTBOUND_OVERFLOW
}
