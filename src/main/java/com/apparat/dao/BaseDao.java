package com.apparat.dao;

import com.apparat.config.ConnectionFactory;
import com.apparat.exception.DataAccessException;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Shared JDBC plumbing for concrete DAOs.
 *
 * RULE (checked in every DAO in this codebase, not just documented — see
 * ARCHITECTURE_DECISIONS.md ADR-8 / CORRECTIONS_LOG.md C16): a DAO method
 * that receives a caller-supplied Connection NEVER calls commit(), rollback(),
 * changes setAutoCommit, or closes that connection. Only #withConnection
 * (used by standalone, self-managing DAO methods) opens/closes its own
 * connection. The Service layer is the only place a transaction begins and
 * ends.
 */
public abstract class BaseDao {

    /** Runs work against a freshly opened, auto-commit connection that this method owns end-to-end — for standalone (non-transactional) DAO calls only. */
    protected <R> R withConnection(SqlFunction<Connection, R> work) {
        try (Connection con = ConnectionFactory.get()) {
            return work.apply(con);
        } catch (SQLException e) {
            throw translateSqlException(e);
        }
    }

    /** Translates a SQLException into the appropriate ApparatException. MySQL error 1062 (duplicate key on booking_slot's unique constraint) is the expected signal of a layer-3 conflict catch — see dao.BookingDao for where that specific translation happens. */
    protected static DataAccessException translateSqlException(SQLException e) {
        return new DataAccessException("A database error occurred. Please try again.", e);
    }

    @FunctionalInterface
    protected interface SqlFunction<T, R> {
        R apply(T t) throws SQLException;
    }
}
