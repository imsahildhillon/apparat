<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<c:set var="pageTitle" value="Dashboard" scope="request"/>
<%@ include file="layout/header.jspf" %>
<%@ include file="layout/nav.jspf" %>

<div class="page-header">
    <h1>Dashboard</h1>
</div>

<div class="grid grid-3">
    <div class="stat-tile">
        <div class="stat-value">${fn:length(upcomingBookings)}</div>
        <div class="stat-label">Upcoming bookings</div>
    </div>
    <c:if test="${not empty quota}">
        <div class="stat-tile">
            <div class="stat-value">${quota.remainingMinutes}</div>
            <div class="stat-label">Quota minutes remaining</div>
        </div>
    </c:if>
    <c:if test="${not empty pendingApprovals}">
        <div class="stat-tile">
            <div class="stat-value">${fn:length(pendingApprovals)}</div>
            <div class="stat-label">Pending approvals</div>
        </div>
    </c:if>
</div>

<div class="card">
    <div class="card-title">Upcoming bookings</div>
    <c:choose>
        <c:when test="${empty upcomingBookings}">
            <div class="empty-state">No upcoming bookings. <a href="${pageContext.request.contextPath}/resources">Browse resources</a> to book one.</div>
        </c:when>
        <c:otherwise>
            <div class="table-wrap">
            <table>
                <thead><tr><th>Resource</th><th>Start</th><th>End</th><th>Status</th></tr></thead>
                <tbody>
                <c:forEach var="b" items="${upcomingBookings}">
                    <tr onclick="location.href='${pageContext.request.contextPath}/bookings/view?id=${b.id}'" style="cursor:pointer;">
                        <td>#${b.resourceId}</td>
                        <td>${b.startAt}</td>
                        <td>${b.endAt}</td>
                        <td>
                            <c:choose>
                                <c:when test="${b.status == 'APPROVED'}"><span class="badge badge-success">Approved</span></c:when>
                                <c:when test="${b.status == 'PENDING'}"><span class="badge badge-warning">Pending</span></c:when>
                                <c:otherwise><span class="badge badge-muted">${b.status}</span></c:otherwise>
                            </c:choose>
                        </td>
                    </tr>
                </c:forEach>
                </tbody>
            </table>
            </div>
        </c:otherwise>
    </c:choose>
</div>

<c:if test="${sessionScope.sessionUser.approver}">
<div class="grid grid-2">
    <div class="card">
        <div class="card-title">Resources under maintenance</div>
        <c:choose>
            <c:when test="${empty resourcesUnderMaintenance}">
                <div class="empty-state">No resources under maintenance.</div>
            </c:when>
            <c:otherwise>
                <c:forEach var="r" items="${resourcesUnderMaintenance}">
                    <div style="padding:8px 0;border-bottom:1px solid var(--color-border);">
                        <a href="${pageContext.request.contextPath}/resources/view?id=${r.id}"><strong>${r.code}</strong> - ${r.name}</a>
                    </div>
                </c:forEach>
            </c:otherwise>
        </c:choose>
    </div>
    <div class="card">
        <div class="card-title">Today's bookings</div>
        <c:choose>
            <c:when test="${empty todaysBookings}">
                <div class="empty-state">Nothing scheduled today.</div>
            </c:when>
            <c:otherwise>
                <c:forEach var="b" items="${todaysBookings}">
                    <div style="padding:8px 0;border-bottom:1px solid var(--color-border);">
                        Booking #${b.id} - ${b.startAt} to ${b.endAt}
                    </div>
                </c:forEach>
            </c:otherwise>
        </c:choose>
    </div>
</div>
</c:if>

<%@ include file="layout/footer.jspf" %>
