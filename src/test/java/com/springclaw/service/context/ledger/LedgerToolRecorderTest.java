package com.springclaw.service.context.ledger;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerToolRecorderTest {

    @Test
    void recordAndDrainRoundTrip() {
        LedgerToolRecorder recorder = new LedgerToolRecorder();
        recorder.record("readFile", "[\"a.java\"]", "content");
        recorder.record("runCmd", "[\"ls\"]", "ok");

        List<ToolRecord> drained = recorder.drain();
        assertThat(drained).hasSize(2);
        assertThat(drained.get(0).name()).isEqualTo("readFile");
        assertThat(drained.get(0).argsJson()).isEqualTo("[\"a.java\"]");
        assertThat(drained.get(0).resultText()).isEqualTo("content");

        // drain 后清空
        assertThat(recorder.drain()).isEmpty();
        assertThat(recorder.isEmpty()).isTrue();
    }

    @Test
    void nullsAreSanitized() {
        LedgerToolRecorder recorder = new LedgerToolRecorder();
        recorder.record(null, null, null);
        ToolRecord record = recorder.drain().get(0);
        assertThat(record.name()).isEmpty();
        assertThat(record.argsJson()).isEmpty();
        assertThat(record.resultText()).isEmpty();
    }

    @Test
    void argsAndResultAreTruncatedToCaps() {
        LedgerToolRecorder recorder = new LedgerToolRecorder();
        recorder.record("t", "a".repeat(LedgerToolRecorder.MAX_ARGS_CHARS + 100),
                "r".repeat(LedgerToolRecorder.MAX_RESULT_CHARS + 100));
        ToolRecord record = recorder.drain().get(0);
        assertThat(record.argsJson().length())
                .isLessThanOrEqualTo(LedgerToolRecorder.MAX_ARGS_CHARS + "...<truncated>".length());
        assertThat(record.argsJson()).endsWith("<truncated>");
        assertThat(record.resultText())
                .endsWith("<truncated>");
    }

    @Test
    void overflowDropsBeyondLimitAndMarks() {
        LedgerToolRecorder recorder = new LedgerToolRecorder();
        for (int i = 0; i < LedgerToolRecorder.MAX_RECORDS_PER_STEP + 5; i++) {
            recorder.record("t" + i, "[]", "r");
        }
        assertThat(recorder.isOverflowed()).isTrue();
        assertThat(recorder.drain()).hasSize(LedgerToolRecorder.MAX_RECORDS_PER_STEP);

        recorder.resetOverflow();
        assertThat(recorder.isOverflowed()).isFalse();
    }
}
