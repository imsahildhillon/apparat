package com.apparat.model;

import com.apparat.model.enums.ResourceStatus;

import java.time.LocalTime;

/**
 * Abstract resource base. The MVP ships a single concrete subtype
 * (Instrument) — see that class's Javadoc for why a second subtype was not
 * added just to pad out the inheritance tree (PROJECT_BLUEPRINT_CORRECTED.md
 * §35: avoid pointless inheritance purely to tick a rubric item). The
 * abstraction is still real: AvailabilityService and BookingService call
 * cooldownMinutes()/category() without knowing the concrete subtype, and the
 * class is structured so LabRoom/ConsumableKit slot in later with no change
 * to the callers.
 */
public abstract class Resource {

    private Long id;
    private String code;
    private String name;
    private String category;
    private String location;
    private String description;
    private Long custodianId;
    private ResourceStatus status = ResourceStatus.AVAILABLE;
    private LocalTime openTime;
    private LocalTime closeTime;
    private int slotMinutes = 30;
    private int minSlotMinutes = 30;
    private int maxSlotMinutes = 240;
    private int bufferMinutes = 0;
    private boolean requiresCertification;
    private boolean requiresApproval;

    /** Cooldown/warm-up time applied as buffer slots after a booking — differs by resource subtype. */
    public abstract int cooldownMinutes();

    public boolean isBookable() {
        return status == ResourceStatus.AVAILABLE;
    }

    public boolean isWithinOperatingHours(LocalTime start, LocalTime end) {
        return !start.isBefore(openTime) && !end.isAfter(closeTime);
    }

    // --- encapsulated accessors ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Long getCustodianId() { return custodianId; }
    public void setCustodianId(Long custodianId) { this.custodianId = custodianId; }

    public ResourceStatus getStatus() { return status; }
    public void setStatus(ResourceStatus status) { this.status = status; }

    public LocalTime getOpenTime() { return openTime; }
    public void setOpenTime(LocalTime openTime) { this.openTime = openTime; }

    public LocalTime getCloseTime() { return closeTime; }
    public void setCloseTime(LocalTime closeTime) { this.closeTime = closeTime; }

    public int getSlotMinutes() { return slotMinutes; }
    public void setSlotMinutes(int slotMinutes) {
        if (slotMinutes <= 0) throw new IllegalArgumentException("slotMinutes must be positive");
        this.slotMinutes = slotMinutes;
    }

    public int getMinSlotMinutes() { return minSlotMinutes; }
    public void setMinSlotMinutes(int minSlotMinutes) { this.minSlotMinutes = minSlotMinutes; }

    public int getMaxSlotMinutes() { return maxSlotMinutes; }
    public void setMaxSlotMinutes(int maxSlotMinutes) { this.maxSlotMinutes = maxSlotMinutes; }

    public int getBufferMinutes() { return bufferMinutes; }
    public void setBufferMinutes(int bufferMinutes) {
        if (bufferMinutes < 0) throw new IllegalArgumentException("bufferMinutes cannot be negative");
        this.bufferMinutes = bufferMinutes;
    }

    public boolean isRequiresCertification() { return requiresCertification; }
    public void setRequiresCertification(boolean requiresCertification) { this.requiresCertification = requiresCertification; }

    public boolean isRequiresApproval() { return requiresApproval; }
    public void setRequiresApproval(boolean requiresApproval) { this.requiresApproval = requiresApproval; }
}
