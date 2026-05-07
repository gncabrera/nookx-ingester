package com.nookx.ingester.job;

import org.slf4j.MDC;

public final class JobContext {

    public static final String MDC_JOB_RUN_ID = "jobRunId";

    private JobContext() {
        throw new UnsupportedOperationException("This class should never be instantiated");
    }

    public static void bind(final Long jobRunId) {
        if (jobRunId == null) {
            MDC.remove(MDC_JOB_RUN_ID);
            return;
        }
        MDC.put(MDC_JOB_RUN_ID, String.valueOf(jobRunId));
    }

    public static void clear() {
        MDC.remove(MDC_JOB_RUN_ID);
    }

    public static Long currentJobRunId() {
        final String value = MDC.get(MDC_JOB_RUN_ID);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
