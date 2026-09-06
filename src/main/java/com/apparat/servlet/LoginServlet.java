package com.apparat.servlet;

import com.apparat.exception.ApparatException;
import com.apparat.model.dto.SessionUser;
import com.apparat.service.AuthService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

@WebServlet("/login")
public class LoginServlet extends BaseServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (req.getParameter("expired") != null) {
            req.setAttribute("infoMessage", "Your session expired. Please log in again.");
        }
        forward(req, resp, "login.jsp");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        AuthService authService = (AuthService) getServletContext().getAttribute("authService");
        String email = req.getParameter("email");
        char[] password = req.getParameter("password") == null ? new char[0] : req.getParameter("password").toCharArray();

        try {
            // STEP: create a brand-new session AFTER successful auth (session fixation
            // prevention — see PROJECT_BLUEPRINT_CORRECTED.md §23). Never reuse whatever
            // session id the client presented before authenticating.
            SessionUser sessionUser = authService.login(email, password);
            HttpSession old = req.getSession(false);
            if (old != null) old.invalidate();
            HttpSession session = req.getSession(true);
            session.setAttribute("sessionUser", sessionUser);
            session.setMaxInactiveInterval(30 * 60); // 30-minute timeout

            resp.sendRedirect(req.getContextPath() + "/dashboard"); // POST-Redirect-GET
        } catch (ApparatException e) {
            req.setAttribute("submittedEmail", email);
            handleError(req, resp, "login.jsp", e);
        }
    }
}
