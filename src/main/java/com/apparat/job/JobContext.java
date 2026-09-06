package com.apparat.job;

import com.apparat.service.ReportService;
import com.apparat.util.LogWriter;

import java.nio.file.Path;

/**
 * Dependencies a Job needs to run, passed in at execution time by
 * JobWorker rather than stored on the (Serializable) Job object itself —
 * see Job's Javadoc. Not Serializable itself; never checkpointed.
 */
public final class JobContext {
    private final ReportService reportService;
    private final LogWriter logWriter;
    private final Path reportsDir;

    public JobContext(ReportService reportService, LogWriter logWriter, Path reportsDir) {
        this.reportService = reportService;
        this.logWriter = logWriter;
        this.reportsDir = reportsDir;
    }

    public ReportService getReportService() { return reportService; }
    public LogWriter getLogWriter() { return logWriter; }
    public Path getReportsDir() { return reportsDir; }
}
