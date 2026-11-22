package org.districompute.distributed.communication;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Objects;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/** Creates plain or TLS sockets with the runtime's settings (TCP_NODELAY, keep-alive, TLS 1.3/1.2 only). */
public final class Sockets {

    private static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    private Sockets() {
    }

    /**
     * Opens the coordinator's listening socket.
     *
     * @param tls TLS settings
     * @param bindAddress address to bind, for example {@code 0.0.0.0}
     * @param port port, or 0 for an ephemeral port
     * @param backlog accept backlog
     * @return the bound server socket
     * @throws IOException if binding fails or the key store cannot be loaded
     */
    public static ServerSocket listen(TlsConfig tls, String bindAddress, int port, int backlog) throws IOException {
        ServerSocket server;
        if (tls.enabled()) {
            SSLContext context = context(tls, true);
            SSLServerSocket ssl = (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
            ssl.setEnabledProtocols(PROTOCOLS);
            server = ssl;
        } else {
            server = new ServerSocket();
        }
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName(bindAddress), port), backlog);
        return server;
    }

    /**
     * Connects a worker to the coordinator.
     *
     * @param tls TLS settings
     * @param host coordinator host
     * @param port coordinator port
     * @param connectTimeoutMillis connection timeout
     * @return the connected socket (for TLS, after the handshake)
     * @throws IOException if the connection or the TLS handshake fails
     */
    public static Socket connect(TlsConfig tls, String host, int port, int connectTimeoutMillis) throws IOException {
        Socket plain = new Socket();
        configure(plain);
        plain.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
        if (!tls.enabled()) {
            return plain;
        }
        SSLSocket ssl = (SSLSocket) context(tls, false).getSocketFactory().createSocket(plain, host, port, true);
        ssl.setEnabledProtocols(PROTOCOLS);
        if (tls.verifyHostname()) {
            SSLParameters parameters = ssl.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(parameters);
        }
        ssl.setSoTimeout(connectTimeoutMillis);
        ssl.startHandshake();
        ssl.setSoTimeout(0);
        return ssl;
    }

    /**
     * Applies the runtime's options to an accepted or created socket.
     *
     * @param socket the socket
     * @throws IOException if an option cannot be set
     */
    public static void configure(Socket socket) throws IOException {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
    }

    private static SSLContext context(TlsConfig tls, boolean server) throws IOException {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            if (server) {
                Objects.requireNonNull(tls.keyStore(), "tls.keyStore is required on the coordinator");
                KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keys.init(load(tls.keyStore(), tls.keyStorePassword()), password(tls.keyStorePassword()));
                context.init(keys.getKeyManagers(), null, null);
            } else {
                Objects.requireNonNull(tls.trustStore(), "tls.trustStore is required on workers");
                TrustManagerFactory trust = TrustManagerFactory.getInstance(
                        TrustManagerFactory.getDefaultAlgorithm());
                trust.init(load(tls.trustStore(), tls.trustStorePassword()));
                context.init(null, trust.getTrustManagers(), null);
            }
            return context;
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot initialize TLS: " + e.getMessage(), e);
        }
    }

    private static KeyStore load(Path file, String password) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(file)) {
            store.load(in, password(password));
        }
        return store;
    }

    private static char[] password(String password) {
        return password == null ? new char[0] : password.toCharArray();
    }
}
