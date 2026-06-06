package com.springclaw.service.agent.lifecycle;

public record TurnContext(TurnId turnId,
                          RequestIdentity identity,
                          UserInput rawInput,
                          Utterance utterance,
                          ResolvedInput resolvedInput,
                          IntentDecision intentDecision,
                          SlotFrame slots,
                          MemorySlice memorySlice,
                          RunState runState) {
    public TurnContext {
        utterance = utterance == null ? Utterance.unknown() : utterance;
        resolvedInput = resolvedInput == null ? ResolvedInput.unchanged(rawInput == null ? "" : rawInput.text(), "not resolved") : resolvedInput;
        slots = slots == null ? SlotFrame.empty() : slots;
        memorySlice = memorySlice == null ? MemorySlice.empty() : memorySlice;
    }

    public static TurnContext initial(TurnRequest request) {
        TurnRequest safeRequest = request == null
                ? new TurnRequest("", "api", "", "", "", "agent")
                : request;
        UserInput input = new UserInput(safeRequest.message(), safeRequest.message());
        return new TurnContext(
                new TurnId(safeRequest.sessionKey(), safeRequest.requestId()),
                new RequestIdentity(safeRequest.channel(), safeRequest.userId(), safeRequest.responseMode()),
                input,
                Utterance.unknown(),
                ResolvedInput.unchanged(input.text(), "raw input"),
                null,
                SlotFrame.empty(),
                MemorySlice.empty(),
                RunState.started(safeRequest.requestId())
        );
    }

    public String effectiveText() {
        return resolvedInput == null || resolvedInput.text().isBlank() ? rawInput.text() : resolvedInput.text();
    }

    public TurnContext withUtterance(Utterance nextUtterance) {
        return new TurnContext(turnId, identity, rawInput, nextUtterance, resolvedInput, intentDecision, slots, memorySlice, runState);
    }

    public TurnContext withResolvedInput(ResolvedInput nextResolvedInput) {
        return new TurnContext(turnId, identity, rawInput, utterance, nextResolvedInput, intentDecision, slots, memorySlice, runState);
    }

    public TurnContext advanceTo(AgentPhase phase) {
        return new TurnContext(turnId, identity, rawInput, utterance, resolvedInput, intentDecision, slots, memorySlice, runState.advanceTo(phase));
    }
}
