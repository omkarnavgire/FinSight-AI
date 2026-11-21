package com.finsight.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Test-only TLS material, generated at test run time with the JDK's {@code keytool} so no private
 * key is ever committed (P-15). Produces a CA, a server certificate for {@code localhost} signed by
 * that CA (SAN {@code DNS:localhost} only), PEM files for MySQL and PKCS12 truststores for clients.
 */
public final class TestTls {

    public static final String STORE_PASSWORD = "test-only-store-pass";

    public final Path dir;
    public final byte[] caPem;
    public final byte[] serverCertPem;
    public final byte[] serverKeyPem;
    /** PKCS12 truststore that trusts the CA above. */
    public final Path truststore;
    /** PKCS12 truststore that trusts an unrelated CA only. */
    public final Path foreignTruststore;

    private TestTls(Path dir, byte[] caPem, byte[] serverCertPem, byte[] serverKeyPem,
                    Path truststore, Path foreignTruststore) {
        this.dir = dir;
        this.caPem = caPem;
        this.serverCertPem = serverCertPem;
        this.serverKeyPem = serverKeyPem;
        this.truststore = truststore;
        this.foreignTruststore = foreignTruststore;
    }

    /** Generates fresh material in a new temporary directory. */
    public static TestTls create() {
        try {
            var dir = Files.createTempDirectory("finsight-test-tls");
            keytool(dir, "-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048",
                    "-dname", "CN=FinSight Test CA", "-ext", "bc:c", "-validity", "3",
                    "-storetype", "PKCS12", "-keystore", "ca.p12", "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD);
            keytool(dir, "-exportcert", "-alias", "ca", "-keystore", "ca.p12", "-storepass", STORE_PASSWORD,
                    "-rfc", "-file", "ca.pem");
            keytool(dir, "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                    "-dname", "CN=localhost", "-validity", "3",
                    "-storetype", "PKCS12", "-keystore", "server.p12", "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD);
            keytool(dir, "-certreq", "-alias", "server", "-keystore", "server.p12", "-storepass", STORE_PASSWORD,
                    "-file", "server.csr");
            keytool(dir, "-gencert", "-alias", "ca", "-keystore", "ca.p12", "-storepass", STORE_PASSWORD,
                    "-infile", "server.csr", "-outfile", "server-cert.pem", "-rfc",
                    "-ext", "SAN=dns:localhost", "-validity", "3");
            keytool(dir, "-genkeypair", "-alias", "other", "-keyalg", "RSA", "-keysize", "2048",
                    "-dname", "CN=Unrelated Test CA", "-ext", "bc:c", "-validity", "3",
                    "-storetype", "PKCS12", "-keystore", "other.p12", "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD);
            keytool(dir, "-exportcert", "-alias", "other", "-keystore", "other.p12", "-storepass", STORE_PASSWORD,
                    "-rfc", "-file", "other.pem");

            var caPem = Files.readAllBytes(dir.resolve("ca.pem"));
            var serverCertPem = Files.readAllBytes(dir.resolve("server-cert.pem"));
            var serverKeyPem = privateKeyPem(dir.resolve("server.p12"), "server");
            var truststore = truststore(dir, "truststore.p12", dir.resolve("ca.pem"));
            var foreign = truststore(dir, "foreign-truststore.p12", dir.resolve("other.pem"));
            return new TestTls(dir, caPem, serverCertPem, serverKeyPem, truststore, foreign);
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate test TLS material", e);
        }
    }

    private static void keytool(Path dir, String... args) throws IOException, InterruptedException {
        var exe = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool");
        var command = new ArrayList<String>(List.of(exe.toString(), "-noprompt"));
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("keytool " + args[0] + " failed: " + output);
        }
    }

    private static byte[] privateKeyPem(Path keystore, String alias) throws Exception {
        var store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, STORE_PASSWORD.toCharArray());
        }
        var key = (PrivateKey) store.getKey(alias, STORE_PASSWORD.toCharArray());
        var body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getEncoded());
        return ("-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static Path truststore(Path dir, String name, Path certPem) throws Exception {
        X509Certificate cert;
        try (InputStream in = Files.newInputStream(certPem)) {
            cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        var store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setCertificateEntry("ca", cert);
        var path = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(path)) {
            store.store(out, STORE_PASSWORD.toCharArray());
        }
        return path;
    }
}
