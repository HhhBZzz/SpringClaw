package com.springclaw.service.workspace;

/** Ownership was authoritatively checked while holding the row lock and its TTL has expired. */
public class WorkspaceLeaseExpiredException extends SecurityException {

    public WorkspaceLeaseExpiredException(String message) {
        super(message);
    }

    public WorkspaceLeaseExpiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
