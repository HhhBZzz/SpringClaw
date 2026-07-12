package com.springclaw.service.workspace;

import java.time.LocalDateTime;

/** A database-backed exclusive workspace mutation lease. */
public record WorkspaceMutationLease(
        String workspaceId,
        String proposalId,
        long fencingToken,
        LocalDateTime leaseUntil
) { }
