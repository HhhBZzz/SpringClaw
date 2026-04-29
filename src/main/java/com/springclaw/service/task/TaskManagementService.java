package com.springclaw.service.task;

public interface TaskManagementService {

    void deleteTask(String requesterUserId, String requesterRole, String taskId);
}
