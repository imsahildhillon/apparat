package com.apparat.servlet;

import com.apparat.dao.BookingDao;
import com.apparat.model.dto.SessionUser;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@WebServlet("/bookings/mine")
public class MyBookingsServlet extends BaseServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        BookingDao bookingDao = (BookingDao) getServletContext().getAttribute("bookingDao");
        SessionUser actor = currentUser(req);
        req.setAttribute("bookings", bookingDao.findByRequester(actor.getUserId()));
        forward(req, resp, "my-bookings.jsp");
    }
}
