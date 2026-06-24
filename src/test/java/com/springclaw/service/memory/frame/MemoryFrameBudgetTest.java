package com.springclaw.service.memory.frame;

import com.springclaw.runtime.memory.contract.MemoryFrameLayer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryFrameBudgetTest {

    @Test
    void layerBudgetsRespectDefaultSharesAndFiftyPercentCap() {
        MemoryFrameBudget budget = MemoryFrameBudget.of(6000);

        assertThat(budget.limitFor(MemoryFrameLayer.SHORT_TERM)).isEqualTo(2100);
        assertThat(budget.limitFor(MemoryFrameLayer.EPISODIC)).isEqualTo(900);
        assertThat(budget.limitFor(MemoryFrameLayer.SEMANTIC_FACT)).isEqualTo(1200);
        assertThat(budget.limitFor(MemoryFrameLayer.PROJECT)).isEqualTo(1200);
        assertThat(budget.limitFor(MemoryFrameLayer.PROCEDURAL_RULE)).isEqualTo(600);
        assertThat(budget.maxLayerLimit()).isEqualTo(3000);
    }

    @Test
    void budgetRejectsTooSmallOrNonPositiveTotals() {
        assertThatThrownBy(() -> MemoryFrameBudget.of(0))
                .hasMessageContaining("total");
        assertThatThrownBy(() -> MemoryFrameBudget.of(999))
                .hasMessageContaining("total");
    }

    @Test
    void workingMemoryHasNoPersistentMemoryAllocation() {
        MemoryFrameBudget budget = MemoryFrameBudget.of(6000);

        assertThat(budget.limitFor(MemoryFrameLayer.WORKING_MEMORY)).isZero();
    }
}
