package com.springclaw.runtime.lifecycle;

import com.springclaw.runtime.contract.RunEvent;
import com.springclaw.runtime.contract.RunState;

public interface RunLifecycleStore extends RunStateRepository, RunEventStore {

    RunState create(RunState initialState, RunEvent.Draft creationEvent);

    RunState commit(long expectedRevision, RunState nextState, RunEvent.Draft event);
}
