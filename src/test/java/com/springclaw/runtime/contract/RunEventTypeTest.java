package com.springclaw.runtime.contract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunEventTypeTest {

    @Test
    void exposesTurnAndStepBoundaryWireNames() {
        assertThat(RunEventType.TURN_STARTED.wireName()).isEqualTo("turn.started");
        assertThat(RunEventType.TURN_COMPLETED.wireName()).isEqualTo("turn.completed");
        assertThat(RunEventType.STEP_STARTED.wireName()).isEqualTo("step.started");
        assertThat(RunEventType.STEP_COMPLETED.wireName()).isEqualTo("step.completed");
    }

    @Test
    void wireNamesAreUnique() {
        long distinct = java.util.Arrays.stream(RunEventType.values())
                .map(RunEventType::wireName)
                .distinct()
                .count();
        assertThat(distinct).isEqualTo(RunEventType.values().length);
    }

    @Test
    void verificationStartedSupersedesVerificationCompletedWithReadAlias() {
        // 存量 DB 行的 event_json 里是 "verification.completed"——读侧别名保证旧记录可反序列化
        assertThat(RunEventType.fromWireName("verification.started"))
                .isEqualTo(RunEventType.VERIFICATION_STARTED);
        assertThat(RunEventType.fromWireName("verification.completed"))
                .isEqualTo(RunEventType.VERIFICATION_STARTED);
    }

    @Test
    void unknownWireNameStillRejected() {
        assertThatThrownBy(() -> RunEventType.fromWireName("no.such.event"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
