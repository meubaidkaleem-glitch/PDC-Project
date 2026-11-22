package org.districompute.distributed.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.districompute.distributed.communication.TlsConfig;

/** Creates a self-signed coordinator certificate with the JDK's {@code keytool}, for TLS tests and demos. */
public final class TlsFixtures {

    /** Password of the generated stores. */
    public static final String PASSWORD = "changeit-test";

    private TlsFixtures() {
    }

    /**
     * Key store and trust store for a coordinator at {@code localhost} / {@code 127.0.0.1}.
     *
     * @param dir directory for the files
     * @return {@code [coordinator TLS config, worker TLS config]}
     * @throws IOException if keytool fails
     */
    public static TlsConfig[] create(Path dir) throws IOException {
        Path keyStore = dir.resolve("coordinator.p12");
        Path certificate = dir.resolve("coordinator.pem");
        Path trustStore = dir.resolve("workers-truststore.p12");
        keytool("-genkeypair", "-alias", "coordinator", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", keyStore.toString(), "-storepass", PASSWORD,
                "-keypass", PASSWORD);
        keytool("-exportcert", "-alias", "coordinator", "-keystore", keyStore.toString(), "-storepass", PASSWORD,
                "-rfc", "-file", certificate.toString());
        keytool("-importcert", "-noprompt", "-alias", "coordinator", "-file", certificate.toString(),
                "-storetype", "PKCS12", "-keystore", trustStore.toString(), "-storepass", PASSWORD);
        return new TlsConfig[] {
            new TlsConfig(true, keyStore, PASSWORD, null, null, true),
            new TlsConfig(true, null, null, trustStore, PASSWORD, true)
        };
    }

    private static void keytool(String... args) throws IOException {
        Path tool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        if (!Files.isExecutable(tool)) {
            tool = Path.of(System.getProperty("java.home"), "bin", "keytool.exe");
        }
        List<String> command = new ArrayList<>();
        command.add(tool.toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            String output = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("keytool " + args[0] + " failed: " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running keytool", e);
        }
    }
}
