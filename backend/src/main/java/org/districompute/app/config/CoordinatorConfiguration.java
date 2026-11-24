package org.districompute.app.config;

import java.io.IOException;
import java.nio.file.Path;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.distributed.communication.TlsConfig;
import org.districompute.distributed.coordinator.Coordinator;
import org.districompute.distributed.coordinator.CoordinatorConfig;
import org.districompute.distributed.security.SharedSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Starts the embedded DistriCompute-Distributed coordinator (Distributed contract §2.4): the single runtime through
 * which every task runs, and the endpoint worker processes connect to.
 */
@Configuration(proxyBeanMethods = false)
public class CoordinatorConfiguration {

    /** Environment variable with the cluster's shared secret (the same one workers read). */
    public static final String SECRET_VARIABLE = "DISTRICOMPUTE_SECRET";

    private static final Logger LOG = LoggerFactory.getLogger(CoordinatorConfiguration.class);

    /**
     * Core's algorithm registry (the built-in algorithms plus any registered through Core's service loader).
     *
     * @return the registry
     */
    @Bean
    AlgorithmRegistry algorithmRegistry() {
        return AlgorithmRegistry.loadDefault();
    }

    /**
     * The coordinator.
     *
     * @param properties application settings
     * @param registry Core's registry
     * @return the started coordinator
     * @throws IOException if the worker port cannot be bound or a secret file cannot be read
     */
    @Bean(destroyMethod = "close")
    Coordinator coordinator(AppProperties properties, AlgorithmRegistry registry) throws IOException {
        AppProperties.Coordinator c = properties.coordinator();
        CoordinatorConfig.Builder builder = CoordinatorConfig.builder(SharedSecret.of(clusterSecret(properties)))
                .coordinatorId("districompute-application").bindAddress(c.bindAddress()).port(c.port())
                .heartbeat(c.heartbeatInterval(), c.suspectAfter(), c.heartbeatTimeout()).maxWorkers(c.maxWorkers())
                .registry(registry);
        if (c.tlsKeystore() != null) {
            String password = c.tlsKeystorePasswordFile() == null ? "" : SecretFiles.read(c.tlsKeystorePasswordFile());
            builder.tls(new TlsConfig(true, c.tlsKeystore(), password, null, null, true));
        }
        Coordinator coordinator = Coordinator.start(builder.build());
        LOG.info("Embedded coordinator listening for workers on {}:{}{}", c.bindAddress(), coordinator.port(),
                c.tlsKeystore() != null ? " (TLS)" : "");
        return coordinator;
    }

    /**
     * The cluster secret: {@code DISTRICOMPUTE_SECRET}, else the configured file, else a generated file in the data
     * directory (its path is logged, never its content).
     *
     * @param properties settings
     * @return the secret
     * @throws IOException if a file cannot be read or written
     */
    static String clusterSecret(AppProperties properties) throws IOException {
        String fromEnvironment = System.getenv(SECRET_VARIABLE);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment.strip();
        }
        Path file = properties.coordinator().secretFile();
        if (file != null) {
            return SecretFiles.read(file);
        }
        Path generated = properties.dataDir().resolve("cluster.secret");
        boolean existed = generated.toFile().exists();
        String secret = SecretFiles.readOrCreate(generated, 32);
        if (!existed) {
            LOG.warn("No cluster secret configured; generated one in {}. Give this file to the workers "
                    + "(--secret-file) or set {} everywhere.", generated.toAbsolutePath(), SECRET_VARIABLE);
        }
        return secret;
    }
}
