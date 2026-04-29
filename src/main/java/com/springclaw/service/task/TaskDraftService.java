package com.springclaw.service.task;

public interface TaskDraftService {

    TaskCreationDraft parseDraft(String ownerUserId, String channel, String message);
}
