package com.springclaw.runtime.bridge;

import com.springclaw.runtime.lifecycle.RunCoordinator;

@Deprecated(since = "3F", forRemoval = false)
public final class DefaultLegacyRuntimeBridge
        extends DefaultRunLifecycleBridge
        implements LegacyRuntimeBridge {

    public DefaultLegacyRuntimeBridge(RunCoordinator coordinator) {
        super(coordinator);
    }
}
