package org.districompute.distributed.communication;

import java.nio.file.Path;

/**
 * Optional TLS for coordinator–worker connections. The coordinator needs a key store with its certificate; workers
 * need a trust store containing that certificate (or its CA). Workers still authenticate with the shared secret.
 *
 * @param enabled whether TLS is used
 * @param keyStore PKCS#12 key store with the coordinator's key and certificate (coordinator side)
 * @param keyStorePassword password of the key store
 * @param trustStore PKCS#12 trust store with the coordinator's certificate (worker side)
 * @param trustStorePassword password of the trust store
 * @param verifyHostname whether workers check that the certificate names the coordinator's host
 */
public record TlsConfig(boolean enabled, Path keyStore, String keyStorePassword, Path trustStore,
        String trustStorePassword, boolean verifyHostname) {

    /** TLS switched off. */
    public static final TlsConfig DISABLED = new TlsConfig(false, null, null, null, null, true);

    @Override
    public String toString() {
        return "TlsConfig[enabled=" + enabled + ", keyStore=" + keyStore + ", trustStore=" + trustStore
                + ", verifyHostname=" + verifyHostname + "]";
    }
}
