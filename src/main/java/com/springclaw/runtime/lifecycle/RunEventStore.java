package com.springclaw.runtime.lifecycle;

import com.springclaw.runtime.contract.RunEvent;

import java.util.List;

public interface RunEventStore {

    List<RunEvent> findEventsByRunId(String runId);
}
