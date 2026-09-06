<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="${resource.name}" scope="request"/>
<%@ include file="layout/header.jspf" %>
<%@ include file="layout/nav.jspf" %>

<div class="page-header">
    <div>
        <h1>${resource.name}</h1>
        <p>${resource.code} &middot; ${resource.category} &middot; ${resource.location}</p>
    </div>
    <a class="btn btn-primary" href="${pageContext.request.contextPath}/bookings/create?resourceId=${resource.id}&date=${selectedDate}">Book this resource</a>
</div>

<c:if test="${param.maintUpdated == '1'}"><div class="alert alert-success">Maintenance status updated.</div></c:if>
<c:if test="${not empty param.error}"><div class="alert alert-danger">${param.error}</div></c:if>

<div class="grid grid-3">
    <div class="card" style="grid-column:span 2;">
        <div class="card-title">Description</div>
        <p>${resource.description}</p>
        <div class="grid grid-2">
            <div><strong>Operating hours</strong><br>${resource.openTime} – ${resource.closeTime}</div>
            <div><strong>Slot size</strong><br>${resource.slotMinutes} minutes</div>
            <div><strong>Duration range</strong><br>${resource.minSlotMinutes}–${resource.maxSlotMinutes} minutes</div>
            <div><strong>Cooldown / buffer</strong><br>${resource.bufferMinutes} minutes</div>
        </div>
        <c:if test="${resource.requiresCertification}"><p style="margin-top:var(--space-3);"><span class="badge badge-warning">Requires certification</span> (displayed for information — not enforced in this MVP; see docs/CORRECTIONS_LOG.md scope notes)</p></c:if>
        <c:if test="${resource.requiresApproval}"><p><span class="badge badge-muted">Bookings need Technician/Admin approval</span></p></c:if>
    </div>
    <div class="card">
        <div class="card-title">Status</div>
        <c:choose>
            <c:when test="${resource.status == 'AVAILABLE'}"><span class="badge badge-success">Available</span></c:when>
            <c:when test="${resource.status == 'MAINTENANCE'}"><span class="badge badge-danger">Under maintenance</span></c:when>
            <c:otherwise><span class="badge badge-muted">${resource.status}</span></c:otherwise>
        </c:choose>

        <c:if test="${sessionScope.sessionUser.approver}">
            <div style="margin-top:var(--space-4);border-top:1px solid var(--color-border);padding-top:var(--space-4);">
                <div class="card-title">Maintenance (Technician/Admin)</div>
                <c:choose>
                    <c:when test="${resource.status == 'MAINTENANCE'}">
                        <form method="post" action="${pageContext.request.contextPath}/maintenance">
                            <input type="hidden" name="resourceId" value="${resource.id}">
                            <input type="hidden" name="action" value="clear">
                            <button type="submit" class="btn btn-secondary btn-sm">Clear maintenance</button>
                        </form>
                    </c:when>
                    <c:otherwise>
                        <form method="post" action="${pageContext.request.contextPath}/maintenance">
                            <input type="hidden" name="resourceId" value="${resource.id}">
                            <div class="field"><label>Start</label><input type="datetime-local" name="startAt" required></div>
                            <div class="field"><label>End</label><input type="datetime-local" name="endAt" required></div>
                            <div class="field"><label>Reason</label><input type="text" name="reason" placeholder="e.g. Annual calibration"></div>
                            <button type="submit" class="btn btn-danger btn-sm">Schedule maintenance</button>
                        </form>
                    </c:otherwise>
                </c:choose>
            </div>
        </c:if>
    </div>
</div>

<div class="card">
    <div class="card-title">Availability — ${selectedDate}</div>
    <form method="get" action="${pageContext.request.contextPath}/resources/view" style="margin-bottom:var(--space-4);">
        <input type="hidden" name="id" value="${resource.id}">
        <input type="date" name="date" value="${selectedDate}" onchange="this.form.submit()">
    </form>
    <div class="slot-grid">
        <c:forEach var="entry" items="${grid}">
            <div class="slot
                <c:choose>
                    <c:when test="${entry.value == 'FREE'}">slot-free</c:when>
                    <c:when test="${entry.value == 'BOOKED'}">slot-booked</c:when>
                    <c:otherwise>slot-maintenance</c:otherwise>
                </c:choose>">
                ${entry.key}
            </div>
        </c:forEach>
    </div>
    <p class="field-hint" style="margin-top:var(--space-3);">Green = free · Grey = booked · Red = maintenance. Use "Book this resource" above to select an exact time — the booking form re-validates every slot server-side.</p>
</div>

<%@ include file="layout/footer.jspf" %>
