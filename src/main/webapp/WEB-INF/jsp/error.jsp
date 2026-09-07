<%@ page isErrorPage="true" contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Something went wrong - Apparat</title>
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/app.css">
    <style>body{display:flex;align-items:center;justify-content:center;min-height:100vh;}</style>
</head>
<body>
<div class="card" style="width:420px;text-align:center;">
    <h2>Something went wrong</h2>
    <p>
        <c:choose>
            <c:when test="${not empty errorMessage}">${errorMessage}</c:when>
            <c:when test="${not empty exception.message}">${exception.message}</c:when>
            <c:otherwise>The page you requested could not be found or you don't have permission to view it.</c:otherwise>
        </c:choose>
    </p>
    <a class="btn btn-primary" href="${pageContext.request.contextPath}/dashboard">Back to dashboard</a>
</div>
</body>
</html>
