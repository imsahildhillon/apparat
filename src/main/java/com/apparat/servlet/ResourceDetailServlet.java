package com.apparat.servlet;

import com.apparat.exception.ApparatException;
import com.apparat.model.Resource;
import com.apparat.service.AvailabilityService;
import com.apparat.service.ResourceService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.LocalDate;

@WebServlet("/resources/view")
public class ResourceDetailServlet extends BaseServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        ResourceService resourceService = (ResourceService) getServletContext().getAttribute("resourceService");
        AvailabilityService availabilityService = (AvailabilityService) getServletContext().getAttribute("availabilityService");

        try {
            long id = Long.parseLong(req.getParameter("id"));
            Resource resource = resourceService.get(id);

            LocalDate date = req.getParameter("date") != null
                    ? LocalDate.parse(req.getParameter("date"))
                    : LocalDate.now();

            req.setAttribute("resource", resource);
            req.setAttribute("grid", availabilityService.dayGrid(resource, date));
            req.setAttribute("selectedDate", date);
            forward(req, resp, "resource-details.jsp");
        } catch (ApparatException e) {
            handleError(req, resp, "error.jsp", e);
        } catch (NumberFormatException | java.time.format.DateTimeParseException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid resource id or date.");
        }
    }
}
