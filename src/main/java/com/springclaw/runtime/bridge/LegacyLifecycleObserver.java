package com.springclaw.runtime.bridge;

@Deprecated(since = "3F", forRemoval = false)
public class LegacyLifecycleObserver extends RunLifecycleObserver {

    public LegacyLifecycleObserver(
            LegacyRuntimeBridge bridge,
            LegacyRunContextAdapter contextAdapter,
            LegacyExecutionDecisionAdapter decisionAdapter,
            LegacyRunResultAdapter resultAdapter,
            boolean contextSnapshotFactoryEnabled
    ) {
        super(
                bridge,
                contextAdapter,
                decisionAdapter,
                resultAdapter,
                contextSnapshotFactoryEnabled
        );
    }

    public LegacyLifecycleObserver(
            LegacyRuntimeBridge bridge,
            LegacyRunContextAdapter contextAdapter,
            LegacyExecutionDecisionAdapter decisionAdapter,
            LegacyRunResultAdapter resultAdapter
    ) {
        super(bridge, contextAdapter, decisionAdapter, resultAdapter);
    }
}
