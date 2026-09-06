<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Resources" scope="request"/>
<%@ include file="layout/header.jspf" %>
<%@ include file="layout/nav.jspf" %>

<div class="page-header">
    <h1>Resources</h1>
</div>

<div class="card">
    <form method="get" action="${pageContext.request.contextPath}/resources" class="grid grid-3" style="align-items:end;">
        <div class="field" style="margin-bottom:0;">
            <label for="q">Search</label>
            <input type="text" id="q" name="q" value="${query}" placeholder="Name or code">
        </div>
        <div class="field" style="margin-bottom:0;">
            <label for="status">Status</label>
            <select id="status" name="status">
                <option value="">Any</option>
                <option value="AVAILABLE" ${selectedStatus == 'AVAILABLE' ? 'selected' : ''}>Available</option>
                <option value="MAINTENANCE" ${selectedStatus == 'MAINTENANCE' ? 'selected' : ''}>Maintenance</option>
                <option value="INACTIVE" ${selectedStatus == 'INACTIVE' ? 'selected' : ''}>Inactive</option>
            </select>
        </div>
        <div class="field" style="margin-bottom:0;">
            <button type="submit" class="btn btn-primary">Filter</button>
            <a class="btn btn-secondary" href="${pageContext.request.contextPath}/resources">Reset</a>
        </div>
    </form>
</div>

<c:choose>
    <c:when test="${empty resources}">
        <div class="card"><div class="empty-state">No resources match your filters.</div></div>
    </c:when>
    <c:otherwise>
        <div class="grid grid-3">
            <c:forEach var="r" items="${resources}">
                <div class="card">
                    <div style="display:flex;justify-content:space-between;align-items:start;">
                        <h3>${r.name}</h3>
                        <c:choose>
                            <c:when test="${r.status == 'AVAILABLE'}"><span class="badge badge-success">Available</span></c:when>
                            <c:when test="${r.status == 'MAINTENANCE'}"><span class="badge badge-danger">Maintenance</span></c:when>
                            <c:otherwise><span class="badge badge-muted">${r.status}</span></c:otherwise>
                        </c:choose>
                    </div>
                    <p>${r.code} &middot; ${r.category}</p>
                    <p>${r.location}</p>
                    <c:if test="${r.requiresCertification}"><span class="badge badge-warning">Certification recommended</span></c:if>
                    <c:if test="${r.requiresApproval}"><span class="badge badge-muted">Needs approval</span></c:if>
                    <div style="margin-top:var(--space-3);">
                        <a class="btn btn-secondary btn-sm" href="${pageContext.request.contextPath}/resources/view?id=${r.id}">View details</a>
                    </div>
                </div>
            </c:forEach>
        </div>
    </c:otherwise>
</c:choose>

<%@ include file="layout/footer.jspf" %>
