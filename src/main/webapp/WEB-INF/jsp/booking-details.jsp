<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Booking #${booking.id}" scope="request"/>
<%@ include file="layout/header.jspf" %>
<%@ include file="layout/nav.jspf" %>

<div class="page-header"><h1>Booking #${booking.id}</h1></div>

<c:if test="${param.created == '1'}"><div class="alert alert-success">Booking request submitted.</div></c:if>
<c:if test="${param.cancelled == '1'}"><div class="alert alert-success">Booking cancelled.</div></c:if>
<c:if test="${not empty param.error}"><div class="alert alert-danger">${param.error}</div></c:if>

<div class="card">
    <div class="grid grid-2">
        <div><strong>Resource</strong><br><a href="${pageContext.request.contextPath}/resources/view?id=${resource.id}">${resource.name} (${resource.code})</a></div>
        <div><strong>Status</strong><br>
            <c:choose>
                <c:when test="${booking.status == 'APPROVED'}"><span class="badge badge-success">Approved</span></c:when>
                <c:when test="${booking.status == 'PENDING'}"><span class="badge badge-warning">Pending approval</span></c:when>
                <c:when test="${booking.status == 'REJECTED' || booking.status == 'CANCELLED'}"><span class="badge badge-danger">${booking.status}</span></c:when>
                <c:otherwise><span class="badge badge-muted">${booking.status}</span></c:otherwise>
            </c:choose>
        </div>
        <div><strong>Start</strong><br>${booking.startAt}</div>
        <div><strong>End</strong><br>${booking.endAt}</div>
        <div><strong>Purpose</strong><br>${not empty booking.purpose ? booking.purpose : '—'}</div>
        <c:if test="${not empty booking.cancelReason}"><div><strong>Reason</strong><br>${booking.cancelReason}</div></c:if>
    </div>

    <c:if test="${booking.status == 'PENDING' || booking.status == 'APPROVED'}">
        <form method="post" action="${pageContext.request.contextPath}/bookings/view" style="margin-top:var(--space-4);border-top:1px solid var(--color-border);padding-top:var(--space-4);"
              onsubmit="return confirm('Cancel this booking?');">
            <input type="hidden" name="id" value="${booking.id}">
            <input type="text" name="reason" placeholder="Reason for cancellation (optional)" style="width:260px;display:inline-block;margin-right:8px;">
            <button type="submit" class="btn btn-danger">Cancel booking</button>
        </form>
    </c:if>
</div>

<%@ include file="layout/footer.jspf" %>
