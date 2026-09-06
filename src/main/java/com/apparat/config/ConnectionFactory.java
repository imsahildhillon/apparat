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

    private ConnectionFactory() { }

    public static Connection get() throws SQLException {
        AppConfig cfg = AppConfig.get();
        return DriverManager.getConnection(cfg.dbUrl(), cfg.dbUser(), cfg.dbPassword());
    }
}
