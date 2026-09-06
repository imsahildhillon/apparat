package com.apparat.model.enums;

/** Deliberately kept small — two job types is enough to demonstrate the outbox pattern without a job-type explosion. */
public enum JobType {
    SEND_REMINDER,
    GENERATE_REPORT
}
