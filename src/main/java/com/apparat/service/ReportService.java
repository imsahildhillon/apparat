package com.apparat.service;

import com.apparat.config.ConnectionFactory;
import com.apparat.dao.BookingSlotDao;
import com.apparat.dao.ResourceDao;
import com.apparat.exception.DataAccessException;
import com.apparat.model.Resource;
import com.apparat.util.CsvUtil;

import java.io.OutputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resource utilization report — real data only. Utilization % is
 * occupied-minutes (from booking_slot, excluding buffer slots) divided by
 * total available minutes in the window (operating hours * days), computed
 * from actual database rows — see PROJECT_BLUEPRINT_CORRECTED.md §14
 * "no fake analytics."
 */
public class ReportService {

    private final ResourceDao resourceDao;
    private final BookingSlotDao bookingSlotDao;

    public ReportService(ResourceDao resourceDao, BookingSlotDao bookingSlotDao) {
        this.resourceDao = resourceDao;
        this.bookingSlotDao = bookingSlotDao;
    }

    public Map<Resource, Double> utilizationByResource(LocalDateTime from, LocalDateTime to) {
        List<Resource> resources = resourceDao.findAll();
        Map<Resource, Double> result = new LinkedHashMap<>();
        try (Connection con = ConnectionFactory.get()) {
            for (Resource r : resources) {
                int occupiedMinutes = bookingSlotDao.totalOccupiedMinutes(con, r.getId(), from, to, r.getSlotMinutes());
                long days = java.time.Duration.between(from, to).toDays();
                long openMinutesPerDay = java.time.Duration.between(r.getOpenTime(), r.getCloseTime()).toMinutes();
                long totalAvailableMinutes = Math.max(1, days * openMinutesPerDay);
                double pct = Math.min(100.0, 100.0 * occupiedMinutes / totalAvailableMinutes);
                result.put(r, Math.round(pct * 10) / 10.0);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not compute utilization report.", e);
        }
        return result;
    }

    /** Writes the utilization report as CSV — a real file.GENERATE_REPORT background job (job.ReportJob) calls this same method; the report screen's synchronous "download now" button also calls it directly for the MVP, since the dataset here is small enough not to need the async path for every request (see README "Known limitations"). */
    public void writeUtilizationCsv(OutputStream out, Map<Resource, Double> utilization) {
        List<String> headers = List.of("resource_code", "resource_name", "category", "utilization_percent");
        List<List<String>> rows = new ArrayList<>();
        for (Map.Entry<Resource, Double> entry : utilization.entrySet()) {
            Resource r = entry.getKey();
            rows.add(List.of(r.getCode(), r.getName(), r.getCategory(), String.valueOf(entry.getValue())));
        }
        CsvUtil.writeCsv(out, headers, rows);
    }
}
