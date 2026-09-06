package com.apparat.dao;

import java.util.List;
import java.util.Optional;

/**
 * Abstraction over persistence — services depend on this, not on a concrete
 * DAO or on java.sql directly, which is what keeps the service layer
 * unit-testable and swappable. Standalone (self-connection-managing)
 * operations only; DAOs that also need to participate in a caller-owned
 * transaction expose an additional overload taking a Connection (see
 * BaseDao and e.g. BookingDao#insert(Connection, Booking)) — see
 * ARCHITECTURE_DECISIONS.md ADR-8 for why the Service layer, never the DAO,
 * owns the transaction boundary.
 */
public interface Dao<T, ID> {
    Optional<T> findById(ID id);
    List<T> findAll();
}
