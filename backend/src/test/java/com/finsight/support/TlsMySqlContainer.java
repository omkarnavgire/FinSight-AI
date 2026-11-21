package com.finsight.support;

import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.images.builder.Transferable;

/**
 * A MySQL 8 Testcontainer that serves TLS with the test CA's server certificate for {@code localhost}
 * (SAN {@code DNS:localhost} only), so clients can run real {@code VERIFY_IDENTITY} checks.
 */
public final class TlsMySqlContainer {

    private TlsMySqlContainer() {}

    /** Creates (does not start) a TLS-enabled MySQL container. */
    public static MySQLContainer<?> create(TestTls tls, String database) {
        return new MySQLContainer<>("mysql:8.0")
                .withDatabaseName(database)
                .withUsername("finsight")
                .withPassword("finsight_it_pw")
                .withCopyToContainer(Transferable.of(tls.caPem, 0644), "/etc/mysql/tls/ca.pem")
                .withCopyToContainer(Transferable.of(tls.serverCertPem, 0644), "/etc/mysql/tls/server-cert.pem")
                .withCopyToContainer(Transferable.of(tls.serverKeyPem, 0644), "/etc/mysql/tls/server-key.pem")
                .withCommand("--ssl-ca=/etc/mysql/tls/ca.pem",
                        "--ssl-cert=/etc/mysql/tls/server-cert.pem",
                        "--ssl-key=/etc/mysql/tls/server-key.pem");
    }

    /** JDBC URL with the given host name and the container's mapped port, no TLS flags. */
    public static String url(MySQLContainer<?> mysql, String host) {
        return "jdbc:mysql://" + host + ":" + mysql.getMappedPort(3306) + "/" + mysql.getDatabaseName()
                + "?serverTimezone=UTC";
    }
}
