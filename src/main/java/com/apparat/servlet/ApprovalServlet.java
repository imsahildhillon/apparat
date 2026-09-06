package com.apparat.servlet;

import com.apparat.exception.ApparatException;
import com.apparat.model.dto.SessionUser;
import com.apparat.service.ApprovalService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** Role-restricted by AuthFilter's "/approvals" prefix AND re-checked in ApprovalService — see PROJECT_BLUEPRINT_CORRECTED.md §27. */
@WebServlet("/approvals")
public class ApprovalServlet extends BaseServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        ApprovalService approvalService = (ApprovalService) getServletContext().getAttribute("approvalService");
        req.setAttribute("pendingBookings", approvalService.pending());
        forward(req, resp, "approvals.jsp");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        ApprovalService approvalService = (ApprovalService) getServletContext().getAttribute("approvalService");
        SessionUser actor = currentUser(req);
        long bookingId = Long.parseLong(req.getParameter("bookingId"));
        String action = req.getParameter("action");

        try {
            if ("approve".equals(action)) {
                approvalService.approve(actor, bookingId);
            } else if ("reject".equals(action)) {
                approvalService.reject(actor, bookingId, req.getParameter("reason"));
            }
            resp.sendRedirect(req.getContextPath() + "/approvals?updated=1");
        } catch (ApparatException e) {
            resp.sendRedirect(req.getContextPath() + "/approvals?error="
                    + java.net.URLEncoder.encode(e.getMessage(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
