<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Profile" scope="request"/>
<%@ include file="layout/header.jspf" %>
<%@ include file="layout/nav.jspf" %>

<div class="page-header"><h1>Profile</h1></div>

<div class="grid grid-2">
    <div class="card">
        <div class="card-title">Account</div>
        <p><strong>${user.fullName}</strong></p>
        <p>${user.email}</p>
        <p><span class="role-badge">${user.role}</span></p>
        <p>${not empty user.department ? user.department : ''}</p>
    </div>
    <c:if test="${not empty quota}">
    <div class="card">
        <div class="card-title">Weekly quota</div>
        <div class="utilization-bar-track">
            <div class="utilization-bar-fill" style="width:${quota.usedMinutes * 100 / quota.limitMinutes}%;"></div>
        </div>
        <p class="field-hint" style="margin-top:8px;">${quota.usedMinutes} of ${quota.limitMinutes} minutes used this week (ISO week ${quota.isoWeek}, ${quota.isoYear}).</p>
    </div>
    </c:if>
</div>

<%@ include file="layout/footer.jspf" %>
