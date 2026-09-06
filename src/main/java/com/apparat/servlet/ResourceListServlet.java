package com.apparat.servlet;

import com.apparat.model.Resource;
import com.apparat.model.enums.ResourceStatus;
import com.apparat.service.ResourceService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

@WebServlet("/resources")
public class ResourceListServlet extends BaseServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        ResourceService resourceService = (ResourceService) getServletContext().getAttribute("resourceService");

        String category = req.getParameter("category");
        String query = req.getParameter("q");
        String statusParam = req.getParameter("status");
        ResourceStatus status = null;
        if (statusParam != null && !statusParam.isBlank()) {
            try {
                status = ResourceStatus.valueOf(statusParam);
            } catch (IllegalArgumentException ignored) { /* unrecognised filter value — treat as "no filter" */ }
        }

        List<Resource> resources = resourceService.browse(category, status, query);
        req.setAttribute("resources", resources);
        req.setAttribute("selectedCategory", category);
        req.setAttribute("selectedStatus", statusParam);
        req.setAttribute("query", query);
        forward(req, resp, "resources.jsp");
    }
}
