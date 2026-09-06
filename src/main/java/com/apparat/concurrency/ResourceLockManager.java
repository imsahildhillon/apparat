package com.apparat.concurrency;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Layer 1 of the three-layer concurrency defence
 * (PROJECT_BLUEPRINT_CORRECTED.md §18.3 / ARCHITECTURE_DECISIONS.md ADR-4/5).
 *
 * One ReentrantLock per resource, not one global lock — bookings on
 * different instruments proceed fully in parallel; only genuine contention
 * on the SAME resource serializes. ConcurrentHashMap#computeIfAbsent is
 * itself atomic, so two threads racing to create the lock for a
 * not-yet-locked resource are guaranteed to end up sharing the same lock
 * instance (a plain HashMap would not give this guarantee and could hand
 * two threads two different lock objects, which would provide no mutual
 * exclusion at all).
 *
 * IMPORTANT, STATED PLAINLY (do not let a caller treat this as sufficient by
 * itself): this lock is JVM-LOCAL. It reduces wasted work between concurrent
 * request threads inside ONE running Tomcat instance. If Apparat ever ran as
 * two application server instances, each would have its own, uncoordinated
 * lock map. The database (SELECT ... FOR UPDATE plus the booking_slot UNIQUE
 * constraint — layers 2 and 3) is what remains correct regardless of
 * deployment topology; this class is a throughput optimisation layered on
 * top of that, never a substitute for it.
 */
public final class ResourceLockManager {

    private static final ResourceLockManager INSTANCE = new ResourceLockManager();

    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    private ResourceLockManager() { }

    public static ResourceLockManager get() {
        return INSTANCE;
    }

    public ReentrantLock lockFor(Long resourceId) {
        return locks.computeIfAbsent(resourceId, id -> new ReentrantLock());
    }

    /** Test/diagnostic hook — how many distinct resource locks currently exist. */
    public int trackedLockCount() {
        return locks.size();
    }
}
