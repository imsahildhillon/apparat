package com.apparat.model;

/**
 * The only concrete Resource subtype shipped in the MVP — every seeded
 * resource (SEM-01, AFM-01, HPLC-01, XRD-01) is an Instrument. A second
 * subtype (e.g. LabRoom, with cooldownMinutes()==0 and different capacity
 * semantics) was deliberately left out: the full blueprint's Resource
 * hierarchy is documented in PROJECT_BLUEPRINT_CORRECTED.md as the target
 * architecture, but adding a subtype with no real seeded data or UI behind
 * it would be inheritance added only to look complete, which
 * PROJECT_BLUEPRINT_CORRECTED.md §35 explicitly warns against. The
 * abstraction point (Resource#cooldownMinutes, called polymorphically from
 * service.BookingService without an instanceof check) is real and ready for
 * a second subtype to be added later with no change to calling code.
 */
public class Instrument extends Resource {

    @Override
    public int cooldownMinutes() {
        return getBufferMinutes();
    }
}
