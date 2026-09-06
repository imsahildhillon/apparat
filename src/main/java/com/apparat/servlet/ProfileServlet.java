package com.apparat.servlet;

import com.apparat.config.ConnectionFactory;
import com.apparat.dao.QuotaDao;
import com.apparat.dao.UserDao;
import com.apparat.model.User;
import com.apparat.model.dto.SessionUser;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;

@WebServlet("/profile")
public class ProfileServlet extends BaseServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        UserDao userDao = (UserDao) getServletContext().getAttribute("userDao");
        QuotaDao quotaDao = (QuotaDao) getServletContext().getAttribute("quotaDao");
        SessionUser actor = currentUser(req);
        User user = userDao.findById(actor.getUserId()).orElseThrow();

        req.setAttribute("user", user);
        try (Connection con = ConnectionFactory.get()) {
            LocalDate today = LocalDate.now();
            int isoYear = QuotaDao.isoYear(today);
            int isoWeek = QuotaDao.isoWeek(today);
            quotaDao.ensureRowExists(con, actor.getUserId(), isoYear, isoWeek, user.weeklyQuotaMinutes());
            req.setAttribute("quota", quotaDao.find(con, actor.getUserId(), isoYear, isoWeek).orElse(null));
        } catch (SQLException e) {
            req.setAttribute("quota", null);
        }
        forward(req, resp, "profile.jsp");
    }
}
