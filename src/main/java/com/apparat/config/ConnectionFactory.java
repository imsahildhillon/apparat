package com.apparat.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * DriverManager-based connection factory. A Tomcat JNDI DataSource pool is
 * the documented recommended upgrade (README "Known limitations") but is
 * deliberately not required for a correct first implementation — see
 * PROJECT_BLUEPRINT_CORRECTED.md §20.4: "the project must not become
 * dependent on advanced deployment infrastructure before the basic
 * DriverManager-based version works end to end."
 */
public final class ConnectionFactory {

    static {
        // Explicit registration rather than relying solely on JDBC 4 ServiceLoader
        // auto-discovery: under a servlet container, DriverManager's own bootstrap
        // classloader does not always see a driver jar shipped in WEB-INF/lib in
        // time for the first connection request from a background thread this
        // application starts itself (job.SchedulerManager) — forcing the class to
        // load here removes that ordering dependency entirely.
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("MySQL JDBC driver not found on the classpath.", e);
        }
    }

    private ConnectionFactory() { }

    public static Connection get() throws SQLException {
        AppConfig cfg = AppConfig.get();
        return DriverManager.getConnection(cfg.dbUrl(), cfg.dbUser(), cfg.dbPassword());
    }
}
