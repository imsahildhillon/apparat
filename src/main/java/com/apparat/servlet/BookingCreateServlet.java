package com.apparat.servlet;

import com.apparat.dao.UserDao;
import com.apparat.exception.ApparatException;
import com.apparat.model.Resource;
import com.apparat.model.User;
import com.apparat.model.dto.BookingRequest;
import com.apparat.model.dto.BookingResult;
import com.apparat.model.dto.SessionUser;
import com.apparat.service.AvailabilityService;
import com.apparat.service.BookingService;
import com.apparat.service.ResourceService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;

@WebServlet("/bookings/create")
public class BookingCreateServlet extends BaseServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        ResourceService resourceService = (ResourceService) getServletContext().getAttribute("resourceService");
        AvailabilityService availabilityService = (AvailabilityService) getServletContext().getAttribute("availabilityService");

        long resourceId = Long.parseLong(req.getParameter("resourceId"));
        Resource resource = resourceService.get(resourceId);
        LocalDate date = req.getParameter("date") != null ? LocalDate.parse(req.getParameter("date")) : LocalDate.now();

        req.setAttribute("resource", resource);
        req.setAttribute("grid", availabilityService.dayGrid(resource, date));
        req.setAttribute("selectedDate", date);
        forward(req, resp, "create-booking.jsp");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        BookingService bookingService = (BookingService) getServletContext().getAttribute("bookingService");
        UserDao userDao = (UserDao) getServletContext().getAttribute("userDao");
        ResourceService resourceService = (ResourceService) getServletContext().getAttribute("resourceService");
        AvailabilityService availabilityService = (AvailabilityService) getServletContext().getAttribute("availabilityService");
        SessionUser actor = currentUser(req);

        long resourceId = Long.parseLong(req.getParameter("resourceId"));
        try {
            LocalDateTime start = LocalDateTime.parse(req.getParameter("startAt"));
            LocalDateTime end = LocalDateTime.parse(req.getParameter("endAt"));
            String purpose = req.getParameter("purpose");

            User actorUser = userDao.findById(actor.getUserId()).orElseThrow();
            BookingRequest bookingRequest = new BookingRequest(resourceId, start, end, purpose);
            BookingResult result = bookingService.createBooking(bookingRequest, actor, actorUser);

            resp.sendRedirect(req.getContextPath() + "/bookings/view?id=" + result.getBookingId() + "&created=1"); // POST-Redirect-GET
        } catch (ApparatException e) {
            Resource resource = resourceService.get(resourceId);
            LocalDate date = LocalDate.now();
            req.setAttribute("resource", resource);
            req.setAttribute("grid", availabilityService.dayGrid(resource, date));
            req.setAttribute("selectedDate", date);
            handleError(req, resp, "create-booking.jsp", e);
        }
    }
}
