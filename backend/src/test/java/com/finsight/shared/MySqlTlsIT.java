package com.finsight.shared;

import com.finsight.support.TestTls;
import com.finsight.support.TlsMySqlContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verified MySQL TLS with the production driver settings (T-63, Q-4): certificate chain and
 * hostname are both checked, and TLS is really in use.
 */
class MySqlTlsIT {

    private static TestTls tls;
    private static MySQLContainer<?> mysql;

    @BeforeAll
    static void start() {
        tls = TestTls.create();
        mysql = TlsMySqlContainer.create(tls, "finsight_tls_it");
        mysql.start();
    }

    @AfterAll
    static void stop() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    private static Connection connect(String host, String sslMode, Path truststore) throws SQLException {
        var props = new Properties();
        props.setProperty("user", mysql.getUsername());
        props.setProperty("password", mysql.getPassword());
        props.setProperty("sslMode", sslMode);
        props.setProperty("trustCertificateKeyStoreUrl", truststore.toUri().toString());
        props.setProperty("trustCertificateKeyStorePassword", TestTls.STORE_PASSWORD);
        props.setProperty("trustCertificateKeyStoreType", "PKCS12");
        return DriverManager.getConnection(TlsMySqlContainer.url(mysql, host), props);
    }

    private static String sessionCipher(Connection c) throws SQLException {
        try (var s = c.createStatement(); var rs = s.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
            return rs.next() ? rs.getString(2) : "";
        }
    }

    @Test
    @DisplayName("VERIFY_IDENTITY with the right CA and matching host name connects over TLS")
    void verifyIdentity_matchingHost_connects() throws SQLException {
        try (var c = connect("localhost", "VERIFY_IDENTITY", tls.truststore)) {
            assertThat(sessionCipher(c)).as("TLS cipher in use").isNotBlank();
        }
    }

    @Test
    @DisplayName("VERIFY_IDENTITY refuses a host name the certificate does not cover")
    void verifyIdentity_hostMismatch_refused() {
        assertThatThrownBy(() -> connect("127.0.0.1", "VERIFY_IDENTITY", tls.truststore).close())
                .isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("A truststore without the server's CA is refused")
    void untrustedCa_refused() {
        assertThatThrownBy(() -> connect("localhost", "VERIFY_IDENTITY", tls.foreignTruststore).close())
                .isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("Control: VERIFY_CA (no host name check) accepts 127.0.0.1, proving the refusal above is the host check")
    void verifyCa_ignoresHost() throws SQLException {
        try (var c = connect("127.0.0.1", "VERIFY_CA", tls.truststore)) {
            assertThat(sessionCipher(c)).isNotBlank();
        }
    }
}
