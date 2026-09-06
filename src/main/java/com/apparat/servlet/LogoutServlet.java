package com.apparat.servlet;

import com.apparat.model.dto.SessionUser;
import com.apparat.service.AuthService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

@WebServlet("/logout")
public class LogoutServlet extends BaseServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        AuthService authService = (AuthService) getServletContext().getAttribute("authService");
        HttpSession session = req.getSession(false);
        if (session != null) {
            SessionUser sessionUser = (SessionUser) session.getAttribute("sessionUser");
            authService.logout(sessionUser);
            session.invalidate();
        }
        resp.setHeader("Cache-Control", "no-store"); // so Back doesn't reveal a cached authenticated page — see PROJECT_BLUEPRINT_CORRECTED.md §23
        resp.sendRedirect(req.getContextPath() + "/login");
    }
}
