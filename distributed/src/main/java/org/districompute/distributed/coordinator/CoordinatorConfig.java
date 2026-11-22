package org.districompute.distributed.coordinator;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.districompute.distributed.communication.TlsConfig;
import org.districompute.distributed.security.RegistrationAuthenticator;
import org.districompute.distributed.security.SharedSecret;

/**
 * Coordinator settings. Defaults suit a laboratory network; see {@code docs/deployment.md}.
 *
 * @param coordinatorId identifier sent to workers and shown to the Application
 * @param bindAddress address the worker port listens on
 * @param port worker port (0 for an ephemeral port, used by tests)
 * @param secret shared secret for worker registration
 * @param tls TLS settings
 * @param maxMessageBytes largest frame accepted from workers
 * @param registrationTimeout a connection must register within this time
 * @param heartbeatInterval announced to workers: how often they send heartbeats
 * @param suspectAfter heartbeat silence after which a worker becomes {@code SUSPECT} (no new attempts)
 * @param heartbeatTimeout heartbeat silence after which a worker is declared unavailable and disconnected
 * @param maxWorkers maximum number of connected workers
 * @param maxPendingConnections maximum number of connections that have not registered yet
 * @param outboundQueueCapacity frames buffered per connection before the peer is considered stuck
 * @param maxProtocolErrors protocol errors tolerated per connection before it is closed
 * @param allowFaultSimulationWorkers whether workers with fault injection may register
 * @param maxClockSkew tolerated clock difference for registration proofs
 * @param tickInterval how often timeouts are checked
 * @param clock clock for timestamps
 */
public record CoordinatorConfig(
        String coordinatorId,
        String bindAddress,
        int port,
        SharedSecret secret,
        TlsConfig tls,
        int maxMessageBytes,
        Duration registrationTimeout,
        Duration heartbeatInterval,
        Duration suspectAfter,
        Duration heartbeatTimeout,
        int maxWorkers,
        int maxPendingConnections,
        int outboundQueueCapacity,
        int maxProtocolErrors,
        boolean allowFaultSimulationWorkers,
        Duration maxClockSkew,
        Duration tickInterval,
        Clock clock) {

    /** Default worker port. */
    public static final int DEFAULT_PORT = 7650;

    /** Validates the settings. */
    public CoordinatorConfig {
        Objects.requireNonNull(coordinatorId, "coordinatorId");
        Objects.requireNonNull(bindAddress, "bindAddress");
        Objects.requireNonNull(secret, "secret (the cluster's shared secret is required)");
        Objects.requireNonNull(tls, "tls");
        Objects.requireNonNull(clock, "clock");
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        if (maxMessageBytes < 1024) {
            throw new IllegalArgumentException("maxMessageBytes must be at least 1024");
        }
        requirePositive(registrationTimeout, "registrationTimeout");
        requirePositive(heartbeatInterval, "heartbeatInterval");
        requirePositive(maxClockSkew, "maxClockSkew");
        requirePositive(tickInterval, "tickInterval");
        if (suspectAfter.compareTo(heartbeatInterval) <= 0 || heartbeatTimeout.compareTo(suspectAfter) <= 0) {
            throw new IllegalArgumentException("Need heartbeatInterval < suspectAfter < heartbeatTimeout");
        }
        if (maxWorkers < 1 || maxPendingConnections < 1 || outboundQueueCapacity < 16 || maxProtocolErrors < 1) {
            throw new IllegalArgumentException("maxWorkers, maxPendingConnections and maxProtocolErrors must be "
                    + "positive, outboundQueueCapacity at least 16");
        }
    }

    /**
     * Starts a builder with the defaults.
     *
     * @param secret the cluster's shared secret
     * @return the builder
     */
    public static Builder builder(SharedSecret secret) {
        return new Builder(secret);
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /** Builds a {@link CoordinatorConfig}. */
    public static final class Builder {
        private final SharedSecret secret;
        private String coordinatorId = "coordinator@" + localHostName();
        private String bindAddress = "0.0.0.0";
        private int port = DEFAULT_PORT;
        private TlsConfig tls = TlsConfig.DISABLED;
        private int maxMessageBytes = 64 << 20;
        private Duration registrationTimeout = Duration.ofSeconds(10);
        private Duration heartbeatInterval = Duration.ofSeconds(2);
        private Duration suspectAfter = Duration.ofSeconds(5);
        private Duration heartbeatTimeout = Duration.ofSeconds(10);
        private int maxWorkers = 256;
        private int maxPendingConnections = 64;
        private int outboundQueueCapacity = 1024;
        private int maxProtocolErrors = 10;
        private boolean allowFaultSimulationWorkers;
        private Duration maxClockSkew = RegistrationAuthenticator.DEFAULT_MAX_CLOCK_SKEW;
        private Duration tickInterval = Duration.ofMillis(100);
        private Clock clock = Clock.systemUTC();

        private Builder(SharedSecret secret) {
            this.secret = secret;
        }

        /**
         * Sets the coordinator id.
         *
         * @param id the id
         * @return this builder
         */
        public Builder coordinatorId(String id) {
            this.coordinatorId = id;
            return this;
        }

        /**
         * Sets the bind address.
         *
         * @param address the address
         * @return this builder
         */
        public Builder bindAddress(String address) {
            this.bindAddress = address;
            return this;
        }

        /**
         * Sets the worker port.
         *
         * @param value the port, 0 for ephemeral
         * @return this builder
         */
        public Builder port(int value) {
            this.port = value;
            return this;
        }

        /**
         * Sets TLS.
         *
         * @param value TLS settings
         * @return this builder
         */
        public Builder tls(TlsConfig value) {
            this.tls = value;
            return this;
        }

        /**
         * Sets the inbound frame limit.
         *
         * @param bytes the limit
         * @return this builder
         */
        public Builder maxMessageBytes(int bytes) {
            this.maxMessageBytes = bytes;
            return this;
        }

        /**
         * Sets the registration timeout.
         *
         * @param value the timeout
         * @return this builder
         */
        public Builder registrationTimeout(Duration value) {
            this.registrationTimeout = value;
            return this;
        }

        /**
         * Sets the heartbeat timing.
         *
         * @param interval heartbeat interval announced to workers
         * @param suspect silence after which a worker is suspect
         * @param timeout silence after which a worker is disconnected
         * @return this builder
         */
        public Builder heartbeat(Duration interval, Duration suspect, Duration timeout) {
            this.heartbeatInterval = interval;
            this.suspectAfter = suspect;
            this.heartbeatTimeout = timeout;
            return this;
        }

        /**
         * Sets the maximum number of workers.
         *
         * @param value the maximum
         * @return this builder
         */
        public Builder maxWorkers(int value) {
            this.maxWorkers = value;
            return this;
        }

        /**
         * Sets the maximum number of unregistered connections.
         *
         * @param value the maximum
         * @return this builder
         */
        public Builder maxPendingConnections(int value) {
            this.maxPendingConnections = value;
            return this;
        }

        /**
         * Sets the outbound queue capacity per connection.
         *
         * @param value frames
         * @return this builder
         */
        public Builder outboundQueueCapacity(int value) {
            this.outboundQueueCapacity = value;
            return this;
        }

        /**
         * Sets the protocol errors tolerated per connection.
         *
         * @param value the count
         * @return this builder
         */
        public Builder maxProtocolErrors(int value) {
            this.maxProtocolErrors = value;
            return this;
        }

        /**
         * Allows workers with fault injection (experiments only).
         *
         * @param value whether they may register
         * @return this builder
         */
        public Builder allowFaultSimulationWorkers(boolean value) {
            this.allowFaultSimulationWorkers = value;
            return this;
        }

        /**
         * Sets the tolerated clock skew for registration proofs.
         *
         * @param value the window
         * @return this builder
         */
        public Builder maxClockSkew(Duration value) {
            this.maxClockSkew = value;
            return this;
        }

        /**
         * Sets how often timeouts are checked.
         *
         * @param value the interval
         * @return this builder
         */
        public Builder tickInterval(Duration value) {
            this.tickInterval = value;
            return this;
        }

        /**
         * Sets the clock.
         *
         * @param value the clock
         * @return this builder
         */
        public Builder clock(Clock value) {
            this.clock = value;
            return this;
        }

        /**
         * Builds the configuration.
         *
         * @return the validated configuration
         */
        public CoordinatorConfig build() {
            return new CoordinatorConfig(coordinatorId, bindAddress, port, secret, tls, maxMessageBytes,
                    registrationTimeout, heartbeatInterval, suspectAfter, heartbeatTimeout, maxWorkers,
                    maxPendingConnections, outboundQueueCapacity, maxProtocolErrors, allowFaultSimulationWorkers,
                    maxClockSkew, tickInterval, clock);
        }
    }

    static String localHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }
}
