package com.apparat.listener;

import com.apparat.config.AppConfig;
import com.apparat.dao.AuditDao;
import com.apparat.dao.BackgroundJobDao;
import com.apparat.dao.BookingDao;
import com.apparat.dao.BookingSlotDao;
import com.apparat.dao.MaintenanceDao;
import com.apparat.dao.QuotaDao;
import com.apparat.dao.ResourceDao;
import com.apparat.dao.UserDao;
import com.apparat.job.JobContext;
import com.apparat.job.SchedulerManager;
import com.apparat.service.ApprovalService;
import com.apparat.service.AuthService;
import com.apparat.service.AvailabilityService;
import com.apparat.service.BookingService;
import com.apparat.service.MaintenanceService;
import com.apparat.service.ReportService;
import com.apparat.service.ResourceService;
import com.apparat.util.LogWriter;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Application composition root. Wires DAOs -> Services -> puts them in
 * ServletContext attributes (servlets read them out, never construct their
 * own — this is where dependency wiring happens exactly once). Also owns
 * the SchedulerManager's start/stop lifecycle — see
 * PROJECT_BLUEPRINT_CORRECTED.md §18.2: threads must not outlive the web
 * application.
 */
@WebListener
public class AppLifecycleListener implements ServletContextListener {

    private SchedulerManager schedulerManager;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        AppConfig config = AppConfig.get();
        Path dataDir = config.dataDir();
        ensureDataDirs(dataDir);

        LogWriter logWriter = new LogWriter(dataDir);

        UserDao userDao = new UserDao();
        ResourceDao resourceDao = new ResourceDao();
        BookingDao bookingDao = new BookingDao();
        BookingSlotDao bookingSlotDao = new BookingSlotDao();
        MaintenanceDao maintenanceDao = new MaintenanceDao();
        QuotaDao quotaDao = new QuotaDao();
        BackgroundJobDao backgroundJobDao = new BackgroundJobDao();
        AuditDao auditDao = new AuditDao();

        AuthService authService = new AuthService(userDao, logWriter);
        ResourceService resourceService = new ResourceService(resourceDao);
        AvailabilityService availabilityService = new AvailabilityService(bookingDao, maintenanceDao);
        BookingService bookingService = new BookingService(resourceDao, bookingDao, bookingSlotDao, maintenanceDao,
                quotaDao, backgroundJobDao, auditDao, logWriter);
        ApprovalService approvalService = new ApprovalService(bookingDao, bookingSlotDao, quotaDao, backgroundJobDao, auditDao, logWriter);
        MaintenanceService maintenanceService = new MaintenanceService(maintenanceDao, resourceDao, logWriter);
        ReportService reportService = new ReportService(resourceDao, bookingSlotDao);

        var ctx = sce.getServletContext();
        ctx.setAttribute("userDao", userDao);
        ctx.setAttribute("bookingDao", bookingDao);
        ctx.setAttribute("quotaDao", quotaDao);
        ctx.setAttribute("resourceDao", resourceDao);
        ctx.setAttribute("authService", authService);
        ctx.setAttribute("resourceService", resourceService);
        ctx.setAttribute("availabilityService", availabilityService);
        ctx.setAttribute("bookingService", bookingService);
        ctx.setAttribute("approvalService", approvalService);
        ctx.setAttribute("maintenanceService", maintenanceService);
        ctx.setAttribute("reportService", reportService);
        ctx.setAttribute("auditDao", auditDao);
        ctx.setAttribute("logWriter", logWriter);

        JobContext jobContext = new JobContext(reportService, logWriter, dataDir.resolve("reports"));
        schedulerManager = new SchedulerManager(backgroundJobDao, jobContext, dataDir,
                config.jobSchedulerPoolSize(), config.jobWorkerPoolSize(), config.jobPollIntervalSeconds());
        schedulerManager.start();

        logWriter.append("APP_STARTED", null, null);
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        if (schedulerManager != null) {
            schedulerManager.shutdown();
        }
    }

    private void ensureDataDirs(Path dataDir) {
        try {
            Files.createDirectories(dataDir.resolve("logs"));
            Files.createDirectories(dataDir.resolve("reports"));
            Files.createDirectories(dataDir.resolve("jobs"));
        } catch (IOException e) {
            throw new IllegalStateException("Could not create data directories under " + dataDir, e);
        }
    }
}
