<%@ page session="true" %>
<%
    // Simple entry redirect — no business logic here, just routing (see
    // PROJECT_BLUEPRINT_CORRECTED.md's "no business logic in JSP" rule; this
    // is pure navigation, not a decision that touches the database).
    if (session.getAttribute("sessionUser") != null) {
        response.sendRedirect(request.getContextPath() + "/dashboard");
    } else {
        response.sendRedirect(request.getContextPath() + "/login");
    }
%>
