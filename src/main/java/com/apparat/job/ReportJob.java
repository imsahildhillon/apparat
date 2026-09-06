package com.apparat.job;

import com.apparat.model.Resource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Generates the utilization CSV report file on a background worker thread, demonstrating a genuinely asynchronous, non-blocking write to disk driven off the durable job queue. */
public class ReportJob extends Job {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public ReportJob(long jobRecordId, String payload) {
        super(jobRecordId, payload);
    }

    @Override
    public void execute(JobContext ctx) throws IOException {
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(30);
        Map<Resource, Double> utilization = ctx.getReportService().utilizationByResource(from, to);

        Files.createDirectories(ctx.getReportsDir());
        Path file = ctx.getReportsDir().resolve("utilization_report_" + to.format(FILE_TS) + ".csv");
        try (OutputStream out = Files.newOutputStream(file)) {
            ctx.getReportService().writeUtilizationCsv(out, utilization);
        }
        ctx.getLogWriter().append("REPORT_GENERATED", null, "job=" + getJobRecordId() + " file=" + file.getFileName());
    }
}
