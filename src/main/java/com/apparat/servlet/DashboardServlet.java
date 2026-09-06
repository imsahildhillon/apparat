package com.apparat.servlet;

import com.apparat.config.ConnectionFactory;
import com.apparat.dao.BookingDao;
import com.apparat.dao.QuotaDao;
import com.apparat.dao.UserDao;
import com.apparat.model.Booking;
import com.apparat.model.QuotaUsage;
import com.apparat.model.Resource;
import com.apparat.model.User;
import com.apparat.model.dto.SessionUser;
import com.apparat.model.enums.BookingStatus;
import com.apparat.model.enums.ResourceStatus;
import com.apparat.service.ApprovalService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@WebServlet("/dashboard")
public class DashboardServlet extends BaseServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        SessionUser actor = currentUser(req);
        UserDao userDao = (UserDao) getServletContext().getAttribute("userDao");
        BookingDao bookingDao = (BookingDao) getServletContext().getAttribute("bookingDao");
        QuotaDao quotaDao = (QuotaDao) getServletContext().getAttribute("quotaDao");

        User actorUser = userDao.findById(actor.getUserId()).orElseThrow();
        List<Booking> myBookings = bookingDao.findByRequester(actor.getUserId());
        LocalDateTime now = LocalDateTime.now();

        List<Booking> upcoming = myBookings.stream()
                .filter(b -> b.getStatus().isOccupying() && b.getStartAt().isAfter(now))
                .sorted((a, b) -> a.getStartAt().compareTo(b.getStartAt()))
                .limit(5)
                .collect(Collectors.toList());
        List<Booking> recent = myBookings.stream().limit(5).collect(Collectors.toList());

        req.setAttribute("upcomingBookings", upcoming);
        req.setAttribute("recentBookings", recent);

        try (Connection con = ConnectionFactory.get()) {
            LocalDate today = LocalDate.now();
            int isoYear = QuotaDao.isoYear(today);
            int isoWeek = QuotaDao.isoWeek(today);
            quotaDao.ensureRowExists(con, actor.getUserId(), isoYear, isoWeek, actorUser.weeklyQuotaMinutes());
            QuotaUsage quota = quotaDao.find(con, actor.getUserId(), isoYear, isoWeek).orElse(null);
            req.setAttribute("quota", quota);
        } catch (SQLException e) {
            req.setAttribute("quota", null);
        }

        if (actor.isApprover()) {
            ApprovalService approvalService = (ApprovalService) getServletContext().getAttribute("approvalService");
            List<Booking> pending = approvalService.pending();
            req.setAttribute("pendingApprovals", pending);

            List<Resource> allResources = ((com.apparat.service.ResourceService) getServletContext().getAttribute("resourceService")).all();
            List<Resource> underMaintenance = allResources.stream()
                    .filter(r -> r.getStatus() == ResourceStatus.MAINTENANCE)
                    .collect(Collectors.toList());
            req.setAttribute("resourcesUnderMaintenance", underMaintenance);

            List<Booking> todaysBookings = bookingDao.findAll().stream()
                    .filter(b -> b.getStatus().isOccupying() && b.getStartAt().toLocalDate().equals(LocalDate.now()))
                    .collect(Collectors.toList());
            req.setAttribute("todaysBookings", todaysBookings);
        }

        forward(req, resp, "dashboard.jsp");
    }
}
