package com.apparat.servlet;

import com.apparat.exception.ApparatException;
import com.apparat.model.dto.SessionUser;
import com.apparat.service.MaintenanceService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.LocalDateTime;

/** Role-restricted by AuthFilter's "/maintenance" prefix AND re-checked in MaintenanceService — see PROJECT_BLUEPRINT_CORRECTED.md §27: hiding a button is never treated as authorization. */
@WebServlet("/maintenance")
public class MaintenanceServlet extends BaseServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        MaintenanceService maintenanceService = (MaintenanceService) getServletContext().getAttribute("maintenanceService");
        SessionUser actor = currentUser(req);
        Long resourceId = Long.valueOf(req.getParameter("resourceId"));
        String action = req.getParameter("action");

        try {
            if ("clear".equals(action)) {
                maintenanceService.clearMaintenance(actor, resourceId);
            } else {
                LocalDateTime start = LocalDateTime.parse(req.getParameter("startAt"));
                LocalDateTime end = LocalDateTime.parse(req.getParameter("endAt"));
                String reason = req.getParameter("reason");
                maintenanceService.scheduleMaintenance(actor, resourceId, start, end, reason);
            }
            resp.sendRedirect(req.getContextPath() + "/resources/view?id=" + resourceId + "&maintUpdated=1");
        } catch (ApparatException e) {
            resp.sendRedirect(req.getContextPath() + "/resources/view?id=" + resourceId + "&error="
                    + java.net.URLEncoder.encode(e.getMessage(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
