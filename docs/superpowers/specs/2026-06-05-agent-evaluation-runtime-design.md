# SpringClaw Agent Evaluation Runtime Design

## Goal

Make SpringClaw's Agent chain explainable and measurable. The first optimization does not replace the existing UI style or rewrite every engine. It adds a quality score layer to the current primary runtime so a Java engineer, tester, or product manager can see whether intent routing, tool choice, evidence, reflection, final answer, cost, and risk handling were actually good.

## Current Diagnosis

The product currently has three names in play: `SimplifiedOparEngine`, `OparLoopEngine`, and `AgentRuntimeEngine`. The live non-general path is already centered on `AgentRuntimeEngine`; OPAR is a legacy/deep/fallback loop. This makes the product hard to explain because the UI can say `opar` while the backend uses the runtime engine.

The existing verification result is status-based. It records `status`, `sufficient`, and `summary`, but does not grade the quality of the decision, tool execution, evidence, reflection, or final answer. A tool call can be technically successful while the answer is still unusable. This is why self-reflection can appear successful even when the user receives a vague or wrong answer.

## Chosen Approach

Use a controlled Java Agent Runtime with scoring:

1. Keep `AgentRuntimeEngine` as the primary execution runtime for non-general requests.
2. Keep OPAR as a named compatibility/deep mode, not the main reliability claim.
3. Add an `AgentEvaluation` object produced by the runtime after evidence reflection.
4. Send the evaluation through SSE `verification` events.
5. Persist the evaluation summary on `agent_run` and expose enough detail through run trace.
6. Render an operator-grade scorecard in the right Run Trace panel.

This is closer to a LangGraph-style controlled state machine plus Plan-and-Execute and Reflexion, implemented in Java without adopting a heavy external agent framework.

## Score Model

Each run receives these 0-100 scores:

- `routeScore`: intent and capability route match the user's task.
- `toolScore`: selected tools are specific enough, allowed, and successful.
- `evidenceScore`: tool output is concrete, fresh, and directly usable.
- `reflectionScore`: verification catches missing evidence and avoids false success.
- `answerScore`: final answer is grounded in accepted evidence.
- `costScore`: latency, retries, and tool count stay reasonable.
- `riskScore`: risk level, permissions, and confirmation behavior are appropriate.
- `overallScore`: weighted score for product display and regression gating.

Thresholds:

- `>= 85`: strong
- `70-84`: acceptable
- `50-69`: weak
- `< 50`: failed

The score is deterministic in the first version. Future phases may add model-based judging or offline eval datasets, but the live product should not depend on a judge model to know that no weather tool ran for a weather question.

## Backend Data Flow

```text
ChatServiceImpl
-> AgentRuntimeEngine.run
-> CapabilityExecutorRegistry.plan
-> CapabilityExecutorRegistry.execute
-> reflectEvidence
-> AgentEvaluationService.evaluate
-> VerificationResult(status, sufficient, summary, evaluation)
-> AgentRunTraceService.persistRun
-> SSE verification event
```

The runtime should add a trace step named `EVALUATE_RUN` after `REFLECT_EVIDENCE`. This makes evaluation visible as a real stage instead of hidden post-processing.

## Persistence

Add lightweight fields to `agent_run`:

- `quality_score INT NULL`
- `quality_grade VARCHAR(32) NULL`
- `evaluation_json TEXT NULL`

Do not create a large eval subsystem yet. The first version stores one snapshot per request. Future schema work can add `agent_run_evaluation` rows for historical rubric versioning and offline benchmark runs.

## Frontend Behavior

The existing dark SpringClaw console stays visually consistent. The right Run Trace panel gains:

- Overall quality score with grade.
- Seven compact metric bars.
- A short reason list explaining weak scores.
- Verification status remains visible, but no longer acts as the only quality signal.

The tabs must keep consistent width and panel sizing when switching between Run Trace, Tool Calls, Memory, and Logs.

## Testing Strategy

Backend tests should cover:

- Weather intent with `weather` capability gets high route/tool/evidence scores when weather tool succeeds.
- Weather intent with only generic web/search output gets low evidence and reflection scores.
- Empty or failed capability results produce failed/weak evaluation.
- General chat does not require tool scoring.
- Trace persistence stores `quality_score`, `quality_grade`, and JSON detail.

Frontend verification should cover:

- A `verification` event with evaluation data renders the scorecard.
- Missing evaluation data falls back gracefully.
- Right inspector tabs keep consistent layout.

## Non-Goals

This phase does not introduce six separate agent architectures, external LangGraph, AutoGen, CrewAI, or an autonomous task queue. SpringClaw should explain itself as one controlled enterprise Java Agent Runtime that borrows proven ideas from mainstream patterns.
