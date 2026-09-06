<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Reports" scope="request"/>
<%@ include file="../layout/header.jspf" %>
<%@ include file="../layout/nav.jspf" %>

<div class="page-header">
    <h1>Resource Utilization</h1>
    <a class="btn btn-secondary" href="${pageContext.request.contextPath}/reports?format=csv">Download CSV</a>
</div>
<p>Last 30 days (${fromDate} to ${toDate}). Computed from actual booking_slot occupancy — see service.ReportService.</p>

<div class="card">
    <c:choose>
        <c:when test="${empty utilization}">
            <div class="empty-state">No resources to report on.</div>
        </c:when>
        <c:otherwise>
            <div class="table-wrap">
            <table>
                <thead><tr><th>Resource</th><th>Category</th><th style="width:40%;">Utilization</th></tr></thead>
                <tbody>
                <c:forEach var="entry" items="${utilization}">
                    <tr>
                        <td>${entry.key.code} — ${entry.key.name}</td>
                        <td>${entry.key.category}</td>
                        <td>
                            <div class="utilization-bar-track">
                                <div class="utilization-bar-fill" style="width:${entry.value}%;"></div>
                            </div>
                            <span class="field-hint">${entry.value}%</span>
                        </td>
                    </tr>
                </c:forEach>
                </tbody>
            </table>
            </div>
        </c:otherwise>
    </c:choose>
</div>

<%@ include file="../layout/footer.jspf" %>
