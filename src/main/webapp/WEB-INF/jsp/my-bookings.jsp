<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="My Bookings" scope="request"/>
<%@ include file="layout/header.jspf" %>
<%@ include file="layout/nav.jspf" %>

<div class="page-header"><h1>My Bookings</h1></div>

<div class="card">
    <c:choose>
        <c:when test="${empty bookings}">
            <div class="empty-state">You haven't booked anything yet. <a href="${pageContext.request.contextPath}/resources">Browse resources</a>.</div>
        </c:when>
        <c:otherwise>
            <div class="table-wrap">
            <table>
                <thead><tr><th>Booking</th><th>Start</th><th>End</th><th>Purpose</th><th>Status</th></tr></thead>
                <tbody>
                <c:forEach var="b" items="${bookings}">
                    <tr onclick="location.href='${pageContext.request.contextPath}/bookings/view?id=${b.id}'" style="cursor:pointer;">
                        <td>#${b.id}</td>
                        <td>${b.startAt}</td>
                        <td>${b.endAt}</td>
                        <td>${b.purpose}</td>
                        <td>
                            <c:choose>
                                <c:when test="${b.status == 'APPROVED'}"><span class="badge badge-success">Approved</span></c:when>
                                <c:when test="${b.status == 'PENDING'}"><span class="badge badge-warning">Pending</span></c:when>
                                <c:when test="${b.status == 'REJECTED' || b.status == 'CANCELLED'}"><span class="badge badge-danger">${b.status}</span></c:when>
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

<%@ include file="layout/footer.jspf" %>
