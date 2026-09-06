package com.apparat.service;

import com.apparat.dao.UserDao;
import com.apparat.exception.AuthenticationException;
import com.apparat.model.User;
import com.apparat.model.dto.SessionUser;
import com.apparat.util.LogWriter;
import com.apparat.util.PasswordUtil;
import com.apparat.util.ValidationUtil;

import java.util.Arrays;

/**
 * Login is intentionally the only place password verification happens.
 * PasswordUtil never returns or logs a plain password; AuthService clears
 * the char[] copy it holds as soon as it's done with it.
 */
public class AuthService {

    private final UserDao userDao;
    private final LogWriter logWriter;

    public AuthService(UserDao userDao, LogWriter logWriter) {
        this.userDao = userDao;
        this.logWriter = logWriter;
    }

    public SessionUser login(String email, char[] password) {
        ValidationUtil.validateEmail(email);
        try {
            User user = userDao.findByEmail(email)
                    .orElseThrow(() -> new AuthenticationException("Invalid email or password."));
            if (!user.isActive()) {
                throw new AuthenticationException("This account has been deactivated.");
            }
            if (!PasswordUtil.matches(password, user.getPasswordHash())) {
                throw new AuthenticationException("Invalid email or password.");
            }
            SessionUser sessionUser = new SessionUser(user.getId(), user.getFullName(), user.getRole(), user.getDepartment());
            logWriter.append("LOGIN", user.getId(), "email=" + email);
            return sessionUser;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public void logout(SessionUser sessionUser) {
        if (sessionUser != null) {
            logWriter.append("LOGOUT", sessionUser.getUserId(), null);
        }
    }
}
