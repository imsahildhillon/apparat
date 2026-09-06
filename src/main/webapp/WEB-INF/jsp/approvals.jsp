<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Approvals" scope="request"/>
<%@ include file="layout/header.jspf" %>
<%@ include file="layout/nav.jspf" %>

<div class="page-header"><h1>Pending Approvals</h1></div>

<c:if test="${param.updated == '1'}"><div class="alert alert-success">Booking updated.</div></c:if>
<c:if test="${not empty param.error}"><div class="alert alert-danger">${param.error}</div></c:if>

<div class="card">
    <c:choose>
        <c:when test="${empty pendingBookings}">
            <div class="empty-state">No bookings are waiting for approval.</div>
        </c:when>
        <c:otherwise>
            <div class="table-wrap">
            <table>
                <thead><tr><th>Booking</th><th>Resource</th><th>Requester</th><th>Start</th><th>End</th><th>Purpose</th><th></th></tr></thead>
                <tbody>
                <c:forEach var="b" items="${pendingBookings}">
                    <tr>
                        <td>#${b.id}</td>
                        <td>#${b.resourceId}</td>
                        <td>#${b.requesterId}</td>
                        <td>${b.startAt}</td>
                        <td>${b.endAt}</td>
                        <td>${b.purpose}</td>
                        <td style="white-space:nowrap;">
                            <form method="post" action="${pageContext.request.contextPath}/approvals" style="display:inline;">
                                <input type="hidden" name="bookingId" value="${b.id}">
                                <input type="hidden" name="action" value="approve">
                                <button type="submit" class="btn btn-primary btn-sm">Approve</button>
                            </form>
                            <form method="post" action="${pageContext.request.contextPath}/approvals" style="display:inline;"
                                  onsubmit="return fillReason(this);">
                                <input type="hidden" name="bookingId" value="${b.id}">
                                <input type="hidden" name="action" value="reject">
                                <input type="hidden" name="reason" class="reject-reason">
                                <button type="submit" class="btn btn-danger btn-sm">Reject</button>
                            </form>
                        </td>
                    </tr>
                </c:forEach>
                </tbody>
            </table>
            </div>
        </c:otherwise>
    </c:choose>
</div>

<script>
    function fillReason(form) {
        var reason = prompt('Reason for rejecting this booking:');
        if (!reason) return false;
        form.querySelector('.reject-reason').value = reason;
        return true;
    }
</script>

<%@ include file="layout/footer.jspf" %>
