<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ page contentType="text/html;charset=UTF-8" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Sign in - Apparat</title>
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/app.css">
    <style>
        body { display: flex; align-items: center; justify-content: center; min-height: 100vh; }
        .login-card { width: 380px; }
        .login-brand { text-align: center; margin-bottom: var(--space-5); }
        .login-brand .brand { font-size: 22px; }
    </style>
</head>
<body>
<div>
    <div class="login-brand">
        <div class="brand">Apparat</div>
        <div class="brand-sub" style="text-transform:none;letter-spacing:normal;">Shared Laboratory Resource Platform</div>
    </div>
    <div class="card login-card">
        <h2>Sign in</h2>

        <c:if test="${not empty infoMessage}"><div class="alert alert-info">${infoMessage}</div></c:if>
        <c:if test="${not empty errorMessage}"><div class="alert alert-danger">${errorMessage}</div></c:if>

        <form method="post" action="${pageContext.request.contextPath}/login">
            <div class="field">
                <label for="email">Email</label>
                <input type="email" id="email" name="email" value="${submittedEmail}" required autofocus>
            </div>
            <div class="field">
                <label for="password">Password</label>
                <input type="password" id="password" name="password" required minlength="8">
            </div>
            <button type="submit" class="btn btn-primary" style="width:100%;">Sign in</button>
        </form>

        <p style="margin-top:var(--space-5);font-size:12px;">
            Demo accounts (local/dev database only - see README "Demo credentials"):<br>
            student.arjun@apparat.edu - prof.kapoor@apparat.edu - tech.mehta@apparat.edu - admin@apparat.edu<br>
            Password for every seeded account: <code>Demo@123</code>
        </p>
    </div>
</div>
</body>
</html>
