package com.apparat.servlet;

import com.apparat.dao.BookingDao;
import com.apparat.exception.ApparatException;
import com.apparat.exception.AuthorizationException;
import com.apparat.exception.ResourceNotFoundException;
import com.apparat.model.Booking;
import com.apparat.model.dto.SessionUser;
import com.apparat.service.BookingService;
import com.apparat.service.ResourceService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@WebServlet("/bookings/view")
public class BookingDetailServlet extends BaseServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        BookingDao bookingDao = (BookingDao) getServletContext().getAttribute("bookingDao");
        ResourceService resourceService = (ResourceService) getServletContext().getAttribute("resourceService");
        SessionUser actor = currentUser(req);

        try {
            long id = Long.parseLong(req.getParameter("id"));
            Booking booking = bookingDao.findById(id).orElseThrow(() -> new ResourceNotFoundException("Booking", id));
            // Object-level ownership check (IDOR protection) — see PROJECT_BLUEPRINT_CORRECTED.md §23.
            if (!booking.getRequesterId().equals(actor.getUserId()) && !actor.isApprover()) {
                throw new AuthorizationException("You do not have permission to view this booking.");
            }
            req.setAttribute("booking", booking);
            req.setAttribute("resource", resourceService.get(booking.getResourceId()));
            req.setAttribute("created", req.getParameter("created") != null);
            forward(req, resp, "booking-details.jsp");
        } catch (ApparatException e) {
            handleError(req, resp, "error.jsp", e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        BookingService bookingService = (BookingService) getServletContext().getAttribute("bookingService");
        SessionUser actor = currentUser(req);
        long id = Long.parseLong(req.getParameter("id"));
        try {
            bookingService.cancelBooking(actor, id, req.getParameter("reason"));
            resp.sendRedirect(req.getContextPath() + "/bookings/view?id=" + id + "&cancelled=1");
        } catch (ApparatException e) {
            resp.sendRedirect(req.getContextPath() + "/bookings/view?id=" + id + "&error="
                    + java.net.URLEncoder.encode(e.getMessage(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
