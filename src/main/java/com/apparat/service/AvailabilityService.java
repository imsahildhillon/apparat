package com.apparat.service;

import com.apparat.dao.BookingDao;
import com.apparat.dao.MaintenanceDao;
import com.apparat.model.Booking;
import com.apparat.model.MaintenanceWindow;
import com.apparat.model.Resource;
import com.apparat.util.DateUtil;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Computes the day's availability grid for a resource: operating hours minus
 * confirmed/pending bookings minus maintenance windows. Uses a NavigableMap
 * (TreeMap) keyed by slot-start so the grid is naturally time-ordered for
 * the JSP to render, and range lookups (ceilingKey/floorKey) are cheap if
 * the UI grows a "jump to next free slot" feature later.
 */
public class AvailabilityService {

    public enum SlotState { FREE, BOOKED, MAINTENANCE }

    private final BookingDao bookingDao;
    private final MaintenanceDao maintenanceDao;

    public AvailabilityService(BookingDao bookingDao, MaintenanceDao maintenanceDao) {
        this.bookingDao = bookingDao;
        this.maintenanceDao = maintenanceDao;
    }

    public NavigableMap<LocalDateTime, SlotState> dayGrid(Resource resource, LocalDate date) {
        LocalDateTime dayStart = LocalDateTime.of(date, resource.getOpenTime());
        LocalDateTime dayEnd = LocalDateTime.of(date, resource.getCloseTime());
        int slotMinutes = resource.getSlotMinutes();

        NavigableMap<LocalDateTime, SlotState> grid = new TreeMap<>();
        for (LocalDateTime cursor = dayStart; cursor.isBefore(dayEnd); cursor = cursor.plusMinutes(slotMinutes)) {
            grid.put(cursor, SlotState.FREE);
        }

        List<Booking> bookings = bookingDao.findByResourceAndWindow(resource.getId(), dayStart, dayEnd);
        for (Booking booking : bookings) {
            for (LocalDateTime slot : DateUtil.generateOccupiedSlots(booking.getStartAt(), booking.getEndAt(), slotMinutes)) {
                grid.replace(slot, SlotState.BOOKED);
            }
        }

        List<MaintenanceWindow> windows = maintenanceDao.findByResource(resource.getId());
        for (MaintenanceWindow window : windows) {
            for (Map.Entry<LocalDateTime, SlotState> entry : grid.entrySet()) {
                LocalDateTime slotStart = entry.getKey();
                LocalDateTime slotEnd = slotStart.plusMinutes(slotMinutes);
                if (window.overlaps(slotStart, slotEnd)) {
                    grid.put(slotStart, SlotState.MAINTENANCE);
                }
            }
        }
        return grid;
    }

    /** EnumMap tally of the grid — cheap, array-backed, exactly the right collection for a fixed enum keyset (see PROJECT_BLUEPRINT_CORRECTED.md's collection-strategy §14). */
    public Map<SlotState, Integer> tally(NavigableMap<LocalDateTime, SlotState> grid) {
        Map<SlotState, Integer> counts = new EnumMap<>(SlotState.class);
        for (SlotState s : SlotState.values()) counts.put(s, 0);
        for (SlotState s : grid.values()) counts.merge(s, 1, Integer::sum);
        return counts;
    }
}
