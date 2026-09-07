<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="New booking" scope="request"/>
<%@ include file="layout/header.jspf" %>
<%@ include file="layout/nav.jspf" %>

<div class="page-header">
    <div>
        <h1>Book ${resource.name}</h1>
        <p>${resource.code} &middot; slots are ${resource.slotMinutes} minutes &middot; ${resource.minSlotMinutes}-${resource.maxSlotMinutes} min per booking</p>
    </div>
</div>

<c:if test="${not empty errorMessage}"><div class="alert alert-danger">${errorMessage}</div></c:if>

<div class="grid grid-3">
    <div class="card" style="grid-column:span 2;">
        <div class="card-title">Booking details</div>
        <form method="post" action="${pageContext.request.contextPath}/bookings/create">
            <input type="hidden" name="resourceId" value="${resource.id}">
            <div class="grid grid-2">
                <div class="field">
                    <label for="startAt">Start</label>
                    <input type="datetime-local" id="startAt" name="startAt" required
                           step="${resource.slotMinutes * 60}">
                </div>
                <div class="field">
                    <label for="endAt">End</label>
                    <input type="datetime-local" id="endAt" name="endAt" required
                           step="${resource.slotMinutes * 60}">
                </div>
            </div>
            <div class="field">
                <label for="purpose">Purpose</label>
                <textarea id="purpose" name="purpose" rows="3" placeholder="What will you use this resource for?"></textarea>
            </div>
            <div class="field-hint" style="margin-bottom:var(--space-3);">
                Times must align to this resource's ${resource.slotMinutes}-minute slots (e.g. 09:00, 09:30 - not 09:15).
                Misaligned times are rejected, not rounded - see docs/PROJECT_BLUEPRINT_CORRECTED.md section 19.4.
            </div>
            <button type="submit" class="btn btn-primary">Request booking</button>
            <a class="btn btn-secondary" href="${pageContext.request.contextPath}/resources/view?id=${resource.id}">Cancel</a>
        </form>
    </div>

    <div class="card">
        <div class="card-title">Availability - ${selectedDate}</div>
        <p class="field-hint">Click a free slot to fill the start time.</p>
        <div class="slot-grid" style="grid-template-columns:repeat(auto-fill,minmax(70px,1fr));">
            <c:forEach var="entry" items="${grid}">
                <c:choose>
                    <c:when test="${entry.value == 'FREE'}">
                        <div class="slot slot-free" onclick="fillStart('${entry.key}')" style="cursor:pointer;">${entry.key}</div>
                    </c:when>
                    <c:when test="${entry.value == 'BOOKED'}">
                        <div class="slot slot-booked">${entry.key}</div>
                    </c:when>
                    <c:otherwise>
                        <div class="slot slot-maintenance">${entry.key}</div>
                    </c:otherwise>
                </c:choose>
            </c:forEach>
        </div>
    </div>
</div>

<script>
    // Pure UI convenience - the server re-validates alignment, overlap, quota and
    // maintenance regardless of what this script fills in (see BookingCreateServlet
    // -> BookingService#createBooking). Client-side JS is never the source of truth.
    var minDurationMinutes = ${resource.minSlotMinutes};
    function fillStart(isoStart) {
        document.getElementById('startAt').value = isoStart;
        var start = new Date(isoStart);
        var end = new Date(start.getTime() + minDurationMinutes * 60000);
        document.getElementById('endAt').value = toLocalIso(end);
    }
    function toLocalIso(d) {
        function pad(n) { return n < 10 ? '0' + n : n; }
        return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
            + 'T' + pad(d.getHours()) + ':' + pad(d.getMinutes());
    }
</script>

<%@ include file="layout/footer.jspf" %>
