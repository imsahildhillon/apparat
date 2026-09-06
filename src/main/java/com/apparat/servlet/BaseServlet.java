package com.apparat.servlet;

import com.apparat.exception.ApparatException;
import com.apparat.model.dto.SessionUser;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Shared servlet plumbing. NOTE what is deliberately NOT here: no SQL, no
 * business rules — every concrete servlet in this package only reads
 * request parameters, calls a service, sets request attributes, and forwards
 * to (or redirects to) a JSP. See PROJECT_BLUEPRINT_CORRECTED.md §22 — a
 * servlet with `import java.sql.*` in it would be a real architecture bug in
 * this codebase, not just a style complaint.
 */
public abstract class BaseServlet extends HttpServlet {

    /** Set by filter.AuthFilter as a request attribute after session validation — never trust a role passed as a request PARAMETER (that would be attacker-controlled). */
    protected SessionUser currentUser(HttpServletRequest req) {
        return (SessionUser) req.getAttribute("sessionUser");
    }

    protected void forward(HttpServletRequest req, HttpServletResponse resp, String jspPath) throws ServletException, IOException {
        RequestDispatcher dispatcher = req.getRequestDispatcher("/WEB-INF/jsp/" + jspPath);
        dispatcher.forward(req, resp);
    }

    /** Catches ApparatException, puts a user-safe message on the request, and re-forwards to the given JSP — the standard "show the form again with an error" pattern used throughout this package. */
    protected void handleError(HttpServletRequest req, HttpServletResponse resp, String jspPath, ApparatException e)
            throws ServletException, IOException {
        resp.setStatus(e.getStatusCode());
        req.setAttribute("errorMessage", e.getMessage());
        forward(req, resp, jspPath);
    }
}
