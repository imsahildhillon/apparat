package com.apparat.filter;

import com.apparat.model.dto.SessionUser;
import com.apparat.model.enums.Role;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Session validation + role-based path authorization — see
 * PROJECT_BLUEPRINT_CORRECTED.md §23. Uses request.getSession(false)
 * deliberately (never creates an empty session just by being asked).
 *
 * This is layer one of authorization (path-level, "can this role reach this
 * URL at all"); object-level checks (e.g. "can THIS technician approve THIS
 * booking", "is this THEIR OWN booking to cancel") are enforced again in the
 * service layer — see BookingService#cancelBooking and
 * ApprovalService#requireApprover. Hiding a nav link is never treated as
 * authorization by itself (PROJECT_BLUEPRINT_CORRECTED.md §27).
 */
@WebFilter("/*")
public class AuthFilter implements Filter {

    private static final Set<String> PUBLIC_PATHS = Set.of("/login", "/css", "/js", "/error");

    /** Path prefix -> roles allowed. Anything authenticated but not listed here is open to any logged-in role. */
    private static final Map<String, Set<Role>> ROLE_RESTRICTED_PREFIXES = Map.of(
            "/approvals", EnumSet.of(Role.TECHNICIAN, Role.ADMIN),
            "/reports", EnumSet.of(Role.TECHNICIAN, Role.ADMIN),
            "/maintenance", EnumSet.of(Role.TECHNICIAN, Role.ADMIN)
    );

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String path = request.getServletPath();
        if (isPublic(path)) {
            chain.doFilter(request, response);
            return;
        }

        HttpSession session = request.getSession(false);
        SessionUser sessionUser = session == null ? null : (SessionUser) session.getAttribute("sessionUser");
        if (sessionUser == null) {
            response.sendRedirect(request.getContextPath() + "/login?expired=1");
            return;
        }

        for (Map.Entry<String, Set<Role>> entry : ROLE_RESTRICTED_PREFIXES.entrySet()) {
            if (path.startsWith(entry.getKey()) && !entry.getValue().contains(sessionUser.getRole())) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "You do not have permission to view this page.");
                return;
            }
        }

        request.setAttribute("sessionUser", sessionUser);
        chain.doFilter(request, response);
    }

    private boolean isPublic(String path) {
        for (String prefix : PUBLIC_PATHS) {
            if (path.equals(prefix) || path.startsWith(prefix + "/") || path.startsWith(prefix + ".")) return true;
        }
        return false;
    }
}
