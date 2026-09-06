package com.apparat.servlet;

import com.apparat.model.Resource;
import com.apparat.service.ReportService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Role-restricted by AuthFilter's "/reports" prefix — see PROJECT_BLUEPRINT_CORRECTED.md §27 (not in the Student/Faculty nav; enforced server-side too). */
@WebServlet("/reports")
public class ReportServlet extends BaseServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        ReportService reportService = (ReportService) getServletContext().getAttribute("reportService");
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(30);
        Map<Resource, Double> utilization = reportService.utilizationByResource(from, to);

        if ("csv".equals(req.getParameter("format"))) {
            resp.setContentType("text/csv");
            resp.setHeader("Content-Disposition", "attachment; filename=\"utilization_report_"
                    + to.format(DateTimeFormatter.BASIC_ISO_DATE) + ".csv\"");
            reportService.writeUtilizationCsv(resp.getOutputStream(), utilization);
            return;
        }

        req.setAttribute("utilization", utilization);
        req.setAttribute("fromDate", from);
        req.setAttribute("toDate", to);
        forward(req, resp, "reports/dashboard.jsp");
    }
}
