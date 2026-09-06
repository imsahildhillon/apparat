package com.apparat.job;

/**
 * "Sends" a booking-status notification. No real SMTP/email infrastructure
 * is built for the MVP (PROJECT_BLUEPRINT_CORRECTED.md §35 explicitly warns
 * against building notification infrastructure the project doesn't need) —
 * this writes a NOTIFICATION_SENT line to the audit log file, which is
 * enough to demonstrate the outbox pattern and the worker pool genuinely
 * executing queued work, without pretending to be a production mailer.
 */
public class NotificationJob extends Job {

    private static final long serialVersionUID = 1L;

    public NotificationJob(long jobRecordId, String payload) {
        super(jobRecordId, payload);
    }

    @Override
    public void execute(JobContext ctx) {
        ctx.getLogWriter().append("NOTIFICATION_SENT", null, "job=" + getJobRecordId() + " payload=" + getPayload());
    }
}
